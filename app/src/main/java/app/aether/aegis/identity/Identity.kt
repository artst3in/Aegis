package app.aether.aegis.identity

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box

/**
 * Device identity — a Curve25519 (X25519) keypair. The public key IS
 * the identity; no usernames, no accounts.
 *
 * Was on a WireGuard `Key`/`KeyPair` for historical reasons (the
 * WireGuard `tunnel` artifact ships ~2.7 MB of native code we never
 * called). A review flagged it as dead weight; switched
 * to LazySodium's Curve25519 primitives, which we already depended on
 * via PeerCrypto. Saves ~3 MB per APK, no behaviour change — the same
 * curve, same key size, same base64 wire format.
 */
data class Identity(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    init {
        require(privateKey.size == KEY_BYTES) { "privateKey must be $KEY_BYTES bytes" }
        require(publicKey.size == KEY_BYTES) { "publicKey must be $KEY_BYTES bytes" }
    }

    val privateKeyB64: String get() = encode(privateKey)
    val publicKeyB64: String get() = encode(publicKey)

    val deviceId: String get() = publicKeyB64
    val shortId: String get() = deviceId.take(8)

    // ByteArray equality/hash are identity by default — override so two
    // Identities with the same key bytes compare equal (matches the
    // previous data-class semantics over WgKey).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return privateKey.contentEquals(other.privateKey) &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int =
        privateKey.contentHashCode() * 31 + publicKey.contentHashCode()

    companion object {
        const val KEY_BYTES = 32

        fun generate(): Identity {
            val pub = ByteArray(Box.PUBLICKEYBYTES)
            val sec = ByteArray(Box.SECRETKEYBYTES)
            check(sodium.cryptoBoxKeypair(pub, sec)) { "cryptoBoxKeypair failed" }
            return Identity(privateKey = sec, publicKey = pub)
        }

        fun fromPrivateKeyB64(b64: String): Identity {
            val priv = decode(b64) ?: error("invalid base64 identity key")
            require(priv.size == KEY_BYTES) { "private key must be $KEY_BYTES bytes" }
            // Derive public via Curve25519 scalar-mult of the base point.
            // Matches WG's KeyPair(privateKey) constructor that we used
            // before, since both libraries operate on the same curve.
            val pub = ByteArray(KEY_BYTES)
            check(sodium.cryptoScalarMultBase(pub, priv)) { "scalar-mult-base failed" }
            return Identity(privateKey = priv, publicKey = pub)
        }

        private val sodium by lazy { app.aether.aegis.crypto.Sodium.shared }

        private fun encode(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.NO_WRAP)

        private fun decode(s: String): ByteArray? = runCatching {
            Base64.decode(s, Base64.NO_WRAP)
        }.getOrNull()
    }
}
