package app.aether.aegis.lock

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import cash.z.ecc.android.bip39.Mnemonics.WordCount
import cash.z.ecc.android.bip39.toSeed

/**
 * The 24-word BIP39 recovery phrase — Aegis's master key.
 *
 * The phrase (256 bits of entropy) is
 * the root from which the SealCrypto at-rest keypair is derived. It is
 * the *vault*; the PIN is merely the *door*. The PIN can be reset; the
 * phrase is permanent and is the only thing that can recover an account
 * or migrate it to a new device. Lose the phrase AND forget the PIN and
 * the data is gone — there is no backdoor, by design.
 *
 * This object is a thin, Aegis-shaped facade over
 * `cash.z.ecc.android:kotlin-bip39` (Electric Coin Company / Zcash). We
 * deliberately do NOT implement BIP39 ourselves: the checksum bit-packing
 * and `PBKDF2-HMAC-SHA512` seed derivation are exactly the kind of fiddly
 * spec where a bespoke version grows subtle, silent bugs that would
 * corrupt every user's master key. The library is audited and pinned to
 * the official Trezor test vectors in `RecoveryPhraseTest`. Directive:
 * official library only, never hand-rolled crypto on this path.
 *
 * What this object does:
 *  - [generate] a fresh phrase from the OS CSPRNG (tutorial Screen 0),
 *  - [isValid]ate a user-entered phrase (wordlist + checksum) before we
 *    accept it on a recovery / migration screen,
 *  - [deriveSeed] the 64-byte BIP39 seed that Stage 3's keypair
 *    derivation (`Argon2id(seed, profileSalt) → crypto_box_seed_keypair`)
 *    consumes.
 *
 * What this object does NOT do:
 *  - It does not persist anything. The phrase is shown once and written
 *    on paper by the user; only a verification hash + the derived public
 *    material live on disk (Stage 2/3). The plaintext phrase never
 *    touches storage.
 *  - It does not run Argon2id or build the seal keypair — that is the
 *    next layer ([PinKeypair], rewired in Stage 3). [deriveSeed] returns
 *    the raw BIP39 seed and stops there.
 *
 * Aegis fixes the word count at 24 ([WORD_COUNT]) — 256 bits. The
 * library supports 12/15/18/21 as well, but a security app has no reason
 * to offer a weaker phrase, so the shorter counts are rejected here
 * rather than silently accepted.
 */
object RecoveryPhrase {

    /** Aegis mandates a 24-word phrase. 256 bits of entropy + 8 bits of
     *  checksum = 264 bits = 24 × 11-bit wordlist indices. */
    const val WORD_COUNT = 24

    /** Entropy carried by a [WORD_COUNT]-word phrase. Documented so call
     *  sites and reviewers can see the 256-bit guarantee at a glance. */
    const val ENTROPY_BITS = 256

    /** Length of the BIP39 seed [deriveSeed] returns —
     *  `PBKDF2-HMAC-SHA512` emits 64 bytes. */
    const val SEED_BYTES = 64

    /**
     * Generate a fresh 24-word phrase from the OS CSPRNG.
     *
     * The library seeds [MnemonicCode] from a cryptographically secure
     * RNG; we never supply our own entropy here. Returns the words as a
     * `List<String>` because the immediate consumer is the UI (Screen 0
     * displays them for the user to copy onto paper) — Strings are
     * unavoidable at the display boundary.
     *
     * The library's internal `char[]` buffer is wiped via [MnemonicCode.clear]
     * before we return, so the only lingering copy is the display list
     * the caller is about to render and then drop. Callers MUST NOT
     * persist the returned words anywhere.
     */
    fun generate(): List<String> {
        val mc = MnemonicCode(WordCount.COUNT_24)
        try {
            // getWords() splits the validated char buffer into per-word
            // char arrays; copy each into a String for the UI layer.
            return mc.words.map { String(it) }
        } finally {
            // Zero the library's backing char[] now that we've taken our
            // display copy — don't leave the master phrase sitting in a
            // mutable buffer for the GC to scatter.
            mc.clear()
        }
    }

    /**
     * Normalise raw user input into candidate words.
     *
     * BIP39 mnemonics are lowercase and single-space separated. Users
     * paste with stray capitalisation, leading/trailing whitespace, or
     * newlines between words (e.g. copied from a numbered list). We
     * lowercase, collapse any run of whitespace, and drop empties so
     * [isValid] / [deriveSeed] see a clean token list. NFKD is handled
     * inside the library for non-ASCII wordlists; the English list is
     * pure ASCII so lowercasing is sufficient here.
     */
    fun normalize(raw: String): List<String> =
        raw.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    /** [isValid] over a raw string — normalises first. */
    fun isValid(phrase: String): Boolean = isValid(normalize(phrase))

    /**
     * True iff [words] is a valid 24-word BIP39 phrase: every word is in
     * the wordlist AND the trailing checksum bits match. Used to gate
     * the "Forgot PIN → enter phrase" and device-migration screens — we
     * reject a mistyped phrase up front instead of deriving a wrong key
     * and silently failing to decrypt.
     *
     * Wrong word count is rejected here rather than thrown: callers treat
     * the result as a simple boolean for inline field validation.
     */
    fun isValid(words: List<String>): Boolean {
        if (words.size != WORD_COUNT) return false
        val mc = MnemonicCode(words.joinToString(" "))
        return try {
            mc.validate() // throws on bad word or bad checksum
            true
        } catch (e: Exception) {
            // ChecksumException / InvalidWordException / WordCountException
            // all mean "not a usable phrase" — surface as false.
            false
        } finally {
            mc.clear()
        }
    }

    /**
     * Derive the 64-byte BIP39 seed from a validated 24-word phrase.
     *
     * This is the master-key material the next layer feeds into Argon2id
     * to produce the SealCrypto keypair (Stage 3). The seed is
     * `PBKDF2-HMAC-SHA512(phrase, "mnemonic", 2048)` per the BIP39
     * standard — Aegis uses no BIP39 passphrase (empty), so the salt is
     * exactly the constant string "mnemonic".
     *
     * The phrase is validated first ([MnemonicCode.toSeed] with
     * `validate = true`); a bad checksum throws rather than producing a
     * plausible-but-wrong seed. Callers MUST [java.util.Arrays.fill] the
     * returned array to zero once they have folded it into the keypair —
     * this is live master-key material, not a throwaway.
     *
     * @throws IllegalArgumentException if [words] is not exactly 24 words.
     * @throws cash.z.ecc.android.bip39.Mnemonics.ChecksumException (and
     *   siblings) if the phrase is invalid.
     */
    fun deriveSeed(words: List<String>): ByteArray {
        require(words.size == WORD_COUNT) {
            "recovery phrase must be $WORD_COUNT words, got ${words.size}"
        }
        val mc = MnemonicCode(words.joinToString(" "))
        try {
            // toSeed() validates (default) then runs the standard
            // PBKDF2-HMAC-SHA512 with salt "mnemonic" + empty passphrase.
            return mc.toSeed()
        } finally {
            mc.clear()
        }
    }

    /** [deriveSeed] over a raw string — normalises first. */
    fun deriveSeed(phrase: String): ByteArray = deriveSeed(normalize(phrase))

    /** Pre-compiled splitter for [normalize] — any run of whitespace. */
    private val WHITESPACE = Regex("\\s+")
}
