package app.aether.aegis.lock

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import java.security.SecureRandom

/**
 * Derives a deterministic X25519 keypair from the user's PIN.
 *
 * Argon2id(PIN, salt, INTERACTIVE) → 32-byte seed →
 * crypto_box_seed_keypair → (pub, priv).
 *
 * The salt is stored alongside the auth hash in [LockStore]. The pub
 * is cached and is enough to *encrypt* (background pipeline never
 * needs the PIN). The priv is regenerated on every unlock — it is
 * NEVER persisted to disk.
 *
 * This is the asymmetric companion to the existing symmetric
 * [app.aether.aegis.vault.VaultCrypto] (which uses AES-GCM with a PIN-derived
 * key). Asymmetric is required here because the background receive
 * pipeline must be able to encrypt incoming messages without the
 * user having entered their PIN.
 */
object PinKeypair {
    private val sodium = app.aether.aegis.crypto.Sodium.shared

    const val SALT_BYTES = PwHash.SALTBYTES                  // 16
    const val PUB_BYTES = Box.PUBLICKEYBYTES                 // 32
    const val PRIV_BYTES = Box.SECRETKEYBYTES                // 32
    private const val SEED_BYTES = Box.SEEDBYTES             // 32

    /** Generate a fresh 16-byte salt via the OS CSPRNG. */
    fun newSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * Derive (pub, priv) from PIN + salt. Same INTERACTIVE Argon2id
     * tier the auth hash uses (~2 ops, 64 MiB), so unlock cost is
     * roughly doubled (one verify, one derive) but stays under ~1 s
     * on commodity hardware.
     *
     * LEGACY PATH. The seal keypair is now
     * rooted in the 24-word recovery phrase, not the PIN — a 4-8 digit
     * PIN carries only 13-27 bits, so a PIN-derived seal is
     * brute-forceable offline from a seized phone. This method is kept
     * only so the migration (re-seal-on-first-unlock) can re-derive the
     * OLD priv from the PIN to unseal existing rows before re-sealing
     * them under the phrase-rooted key. New enrolments use
     * [deriveFromSeed]. Do NOT use this for fresh keys.
     */
    fun derive(pin: String, salt: ByteArray): KeyPair {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val seed = ByteArray(SEED_BYTES)
        val pwBytes = pin.toByteArray(Charsets.UTF_8)
        val ok = sodium.cryptoPwHash(
            seed,
            seed.size,
            pwBytes,
            pwBytes.size,
            salt,
            PwHash.OPSLIMIT_INTERACTIVE.toLong(),
            NativeLong(PwHash.MEMLIMIT_INTERACTIVE.toLong()),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "Argon2id derive failed" }
        try {
            val pub = ByteArray(PUB_BYTES)
            val priv = ByteArray(PRIV_BYTES)
            val ok2 = sodium.cryptoBoxSeedKeypair(pub, priv, seed)
            check(ok2) { "crypto_box_seed_keypair failed" }
            return KeyPair(pub, priv)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Derive the SealCrypto keypair from the BIP39 recovery-phrase seed
     * ("phrase-rooted encryption"). This is the
     * current path for all new keys.
     *
     * Chain: `phrase → BIP39 seed (64 B, from [RecoveryPhrase.deriveSeed])
     * → Argon2id(seed, salt, MODERATE) → 32-byte seal seed →
     * crypto_box_seed_keypair`.
     *
     * Why Argon2id at all, when the input already carries 256 bits? Two
     * reasons, neither of which is "add entropy" (the phrase has plenty):
     *   1. Domain separation + per-profile salting — two profiles built
     *      from the SAME phrase get DIFFERENT seal keypairs, and the seal
     *      seed is cryptographically distinct from any other use of the
     *      BIP39 seed.
     *   2. It keeps the derivation shape identical to the legacy PIN path
     *      ([derive]) so the surrounding keypair plumbing is unchanged.
     *
     * MODERATE tier (~3 ops, 256 MiB) per the spec: this runs at most
     * once per boot (Model B unwraps a cached priv on subsequent
     * unlocks; Model A re-derives from the phrase the user types), so we
     * can afford more than the INTERACTIVE tier the per-unlock PIN verify
     * uses. SENSITIVE (1 GiB) was rejected — it risks OOM on low-end
     * devices (spec "Argon2id tier: MODERATE").
     *
     * @param seed the 64-byte BIP39 seed. The caller still owns it and
     *        MUST wipe it afterwards; this method does not.
     */
    fun deriveFromSeed(seed: ByteArray, salt: ByteArray): KeyPair {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(seed.size == RecoveryPhrase.SEED_BYTES) {
            "seed must be ${RecoveryPhrase.SEED_BYTES} bytes (BIP39 seed)"
        }
        val sealSeed = ByteArray(SEED_BYTES)
        val ok = sodium.cryptoPwHash(
            sealSeed,
            sealSeed.size,
            seed,
            seed.size,
            salt,
            PwHash.OPSLIMIT_MODERATE.toLong(),
            PwHash.MEMLIMIT_MODERATE,
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "Argon2id (phrase-seed) derive failed" }
        try {
            val pub = ByteArray(PUB_BYTES)
            val priv = ByteArray(PRIV_BYTES)
            val ok2 = sodium.cryptoBoxSeedKeypair(pub, priv, sealSeed)
            check(ok2) { "crypto_box_seed_keypair failed" }
            return KeyPair(pub, priv)
        } finally {
            sealSeed.fill(0)
        }
    }

    /** A derived X25519 keypair. Caller is responsible for wiping
     *  [priv] when done — call [wipe]. */
    data class KeyPair(val pub: ByteArray, val priv: ByteArray) {
        fun wipe() {
            priv.fill(0)
        }

        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
