package app.aether.aegis.lock

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import cash.z.ecc.android.bip39.toSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer tests for [RecoveryPhrase] against the official BIP39
 * (Trezor) test vectors.
 *
 * Why these matter: the 24-word phrase is the master key that roots all
 * at-rest encryption (docs/SPEC_UNBREAKABLE.md). If a future bump of
 * `cash.z.ecc.android:kotlin-bip39` ever changed its wordlist, checksum
 * handling, or PBKDF2 parameters, every user's derived key would shift
 * and their messages would become undecryptable — silently. These
 * vectors are the tripwire: they make such a change fail the build.
 *
 * The expected seeds were computed independently with Python's stdlib
 * (`hashlib.pbkdf2_hmac("sha512", …, b"mnemonic"+passphrase, 2048, 64)`)
 * so this is a genuine cross-implementation check, not the library
 * grading its own homework. The all-zero-entropy mnemonic ("…abandon
 * art") and the TREZOR-passphrase seed are the canonical published
 * BIP39 vectors.
 */
class RecoveryPhraseTest {

    private companion object {
        /** 24-word mnemonic for 32 bytes of all-zero entropy (canonical
         *  BIP39 vector). Last word "art" is the 8-bit checksum landing. */
        const val M24_ALLZERO =
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon art"

        /** Seed for M24_ALLZERO with NO passphrase — this is the value
         *  Aegis actually derives ([RecoveryPhrase.deriveSeed] uses an
         *  empty BIP39 passphrase). Independently computed via Python. */
        const val SEED24_EMPTY =
            "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf70" +
                "5489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840"

        /** Seed for M24_ALLZERO with passphrase "TREZOR" — the published
         *  Trezor vector. Cross-checks the library's PBKDF2 against the
         *  reference, even though Aegis itself never uses a passphrase. */
        const val SEED24_TREZOR =
            "bda85446c68413707090a52022edd26a1c9462295029f2e60cd7c4f2bbd30971" +
                "70af7a4d73245cafa9c3cca8d561a7c3de6f5d4a10be8ed2a5e608d68f92fcc8"

        fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }

    @Test
    fun allZeroEntropy_producesCanonicalMnemonic() {
        // Validates the bundled wordlist + checksum mapping: 32 zero
        // bytes must map to the well-known "…abandon art" phrase.
        val mc = MnemonicCode(ByteArray(32))
        assertEquals(M24_ALLZERO, String(mc.chars))
    }

    @Test
    fun deriveSeed_matchesEmptyPassphraseVector() {
        // The path Aegis uses in production: phrase → 64-byte seed.
        val seed = RecoveryPhrase.deriveSeed(M24_ALLZERO)
        assertEquals(RecoveryPhrase.SEED_BYTES, seed.size)
        assertEquals(SEED24_EMPTY, seed.toHex())
    }

    @Test
    fun library_matchesTrezorPassphraseVector() {
        // Cross-implementation check against the published Trezor vector,
        // exercised through the library directly (Aegis never passes a
        // passphrase, so this can't go through RecoveryPhrase).
        val mc = MnemonicCode(M24_ALLZERO)
        val seed = mc.toSeed("TREZOR".toCharArray())
        assertEquals(SEED24_TREZOR, seed.toHex())
    }

    @Test
    fun generate_produces24ValidWords() {
        val words = RecoveryPhrase.generate()
        assertEquals(RecoveryPhrase.WORD_COUNT, words.size)
        assertTrue("freshly generated phrase must validate", RecoveryPhrase.isValid(words))
    }

    @Test
    fun generate_isHighEntropy() {
        // Two generations must differ — a smoke test that we're pulling
        // from a real CSPRNG and not a fixed/duplicated seed.
        assertNotEquals(RecoveryPhrase.generate(), RecoveryPhrase.generate())
    }

    @Test
    fun isValid_rejectsTamperedChecksum() {
        // Swap the checksum word "art" for a wordlist word that breaks
        // the checksum — must be rejected, not silently accepted.
        val tampered = M24_ALLZERO.removeSuffix("art") + "abandon"
        assertFalse(RecoveryPhrase.isValid(tampered))
    }

    @Test
    fun isValid_rejectsWrongWordCount() {
        val twelve = (1..12).joinToString(" ") { "abandon" }
        assertFalse(RecoveryPhrase.isValid(twelve))
    }

    @Test
    fun isValid_rejectsUnknownWord() {
        // "zzzz" is not in the BIP39 wordlist.
        val bogus = M24_ALLZERO.replaceFirst("abandon", "zzzz")
        assertFalse(RecoveryPhrase.isValid(bogus))
    }

    @Test
    fun normalize_handlesCaseAndWhitespace() {
        // Users paste with capitals, newlines, and stray spacing. After
        // normalisation the phrase must validate AND derive the same seed
        // as the canonical form.
        val messy = "  ABANDON\nabandon   abandon\tabandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon ART  "
        assertTrue(RecoveryPhrase.isValid(messy))
        assertEquals(SEED24_EMPTY, RecoveryPhrase.deriveSeed(messy).toHex())
    }
}
