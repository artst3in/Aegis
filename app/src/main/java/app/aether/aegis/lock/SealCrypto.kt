package app.aether.aegis.lock

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box

/**
 * Anonymous public-key encryption over libsodium's crypto_box_seal.
 *
 * Wire format = `[ ephemeral_pub (32B) || ciphertext (n B) || mac (16B) ]`
 * — total overhead = [Box.SEALBYTES] = 48 bytes.
 *
 * The background receive pipeline calls [seal] using only the cached
 * pub (no PIN required). The UI thread calls [unseal] after the user
 * has unlocked and [PinSession] holds the priv in memory.
 */
object SealCrypto {
    private val sodium = app.aether.aegis.crypto.Sodium.shared

    /** Encrypt [plaintext] to the cached [pub]. Anyone can call this
     *  with the public key — no PIN required. */
    fun seal(plaintext: ByteArray, pub: ByteArray): ByteArray {
        require(pub.size == Box.PUBLICKEYBYTES) { "pub must be ${Box.PUBLICKEYBYTES} bytes" }
        val out = ByteArray(plaintext.size + Box.SEALBYTES)
        val ok = sodium.cryptoBoxSeal(out, plaintext, plaintext.size.toLong(), pub)
        check(ok) { "crypto_box_seal failed" }
        return out
    }

    /** Decrypt a sealed blob. Returns null on auth failure (wrong key,
     *  corrupted bytes, truncated input) — callers fall back to
     *  treating the row as locked. */
    fun unseal(sealed: ByteArray, pub: ByteArray, priv: ByteArray): ByteArray? {
        if (sealed.size < Box.SEALBYTES) return null
        require(pub.size == Box.PUBLICKEYBYTES) { "pub must be ${Box.PUBLICKEYBYTES} bytes" }
        require(priv.size == Box.SECRETKEYBYTES) { "priv must be ${Box.SECRETKEYBYTES} bytes" }
        val out = ByteArray(sealed.size - Box.SEALBYTES)
        val ok = runCatching {
            sodium.cryptoBoxSealOpen(out, sealed, sealed.size.toLong(), pub, priv)
        }.getOrDefault(false)
        return if (ok) out else null
    }
}
