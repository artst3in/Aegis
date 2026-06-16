package app.aether.aegis.cmdauth

import android.content.Context
import android.util.Log
import app.aether.aegis.lock.SealKeyVault
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign

/**
 * Per-device Ed25519 keypair that signs Aegis control commands.
 *
 * This is the cryptographic floor under the separated control channel: a
 * command isn't honoured unless it carries a valid signature, no matter
 * what channel it arrived on or how a vanilla client renders it. Separation
 * (a custom MsgContent type) hides commands and blocks hand-typing by
 * construction; this signature is the invariant that survives a SimpleX
 * behaviour change, a routing bug, or a scripted custom-type send.
 *
 * Design (reviewed + approved):
 *  - **Ed25519, not a MAC.** Asymmetric: a sender signs with its private
 *    key, any receiver verifies with the sender's public key. One scheme
 *    for 1:1 AND groups — a symmetric MAC has no shared key across N group
 *    members. It also binds each command to a specific sender.
 *  - **Dedicated keypair, NOT the seal/identity key.** Seal rotation
 *    (recovery, Model A↔B) must not invalidate every control channel.
 *  - **Priv is TEE-wrapped** via [SealKeyVault] (non-exportable AES-GCM,
 *    never leaves the secure element), so a seized disk image can't forge
 *    commands. Public half sits in plaintext — it's handed to peers in the
 *    hello bootstrap.
 *  - **Sign, don't encrypt.** Bodies aren't secret; SimpleX already
 *    encrypts the transport. Signing proves origin.
 *
 * Locked-state note: [verify] needs only the PEER's public key, so an
 * inbound command can be authenticated even on a locked phone (we then
 * drop transient non-remote commands per spec — but the *verification*
 * itself never depends on unlock state). Signing ([sign]) needs our priv,
 * which is only required for OUTBOUND control while the app is unlocked.
 *
 * Process-wide singleton; the keypair is generated lazily on first use and
 * is per-INSTALL (not per-profile) — it identifies this device's control
 * channel, not a chat identity.
 */
object ControlKeypair {

    private const val TAG = "ControlKeypair"
    private const val STORE = "aegis_control_key"
    private const val KEY_PUB = "ctl_pub_hex"
    private const val KEY_PRIV_BLOB = "ctl_priv_blob_hex"   // TEE-wrapped
    private const val KEY_PRIV_IV = "ctl_priv_iv_hex"
    private const val KEY_PRIV_RAW = "ctl_priv_raw_hex"     // TEE-less fallback only
    private const val VAULT_TAG = "control"

    private val sodium = app.aether.aegis.crypto.Sodium.shared

    /** Cached unwrapped priv. The app is unlocked whenever it sends control,
     *  so caching avoids a Keystore round-trip per signature. Never written
     *  to disk in the clear (the on-disk copy is TEE-wrapped). */
    @Volatile private var cachedPriv: ByteArray? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Our Ed25519 public key (32 bytes), generating the keypair on first
     *  call. This is what we advertise to peers in the hello bootstrap. */
    fun publicKey(ctx: Context): ByteArray {
        ensureGenerated(ctx)
        return prefs(ctx).getString(KEY_PUB, null)!!.hexToBytes()
    }

    /** Sign [payload] with our control private key. Returns the 64-byte
     *  Ed25519 detached signature, or null if the priv can't be loaded. */
    fun sign(ctx: Context, payload: ByteArray): ByteArray? {
        val priv = loadPriv(ctx) ?: return null
        val sig = ByteArray(Sign.BYTES)
        val ok = runCatching {
            // This lazysodium build types cryptoSignDetached's length as Long
            // and cryptoSignVerifyDetached's as Int — keep the two in step.
            sodium.cryptoSignDetached(sig, payload, payload.size.toLong(), priv)
        }.getOrDefault(false)
        return if (ok) sig else null
    }

    /** Verify [sig] over [payload] against a peer's [peerPub] Ed25519 key.
     *  Static, secret-free → works while locked. Wrong key / tampered
     *  payload / malformed input all return false. */
    fun verify(payload: ByteArray, sig: ByteArray, peerPub: ByteArray): Boolean {
        if (sig.size != Sign.BYTES || peerPub.size != Sign.PUBLICKEYBYTES) return false
        return runCatching {
            sodium.cryptoSignVerifyDetached(sig, payload, payload.size, peerPub)
        }.getOrDefault(false)
    }

    @Synchronized
    private fun ensureGenerated(ctx: Context) {
        val p = prefs(ctx)
        val hasPub = p.getString(KEY_PUB, null) != null
        val hasPriv = p.getString(KEY_PRIV_BLOB, null) != null ||
            p.getString(KEY_PRIV_RAW, null) != null
        if (hasPub && hasPriv) return

        val pub = ByteArray(Sign.PUBLICKEYBYTES)
        val priv = ByteArray(Sign.SECRETKEYBYTES)
        check(sodium.cryptoSignKeypair(pub, priv)) { "ed25519 keypair generation failed" }

        val edit = p.edit().putString(KEY_PUB, pub.toHex())
        val wrapped = SealKeyVault.wrap(VAULT_TAG, priv)
        if (wrapped != null) {
            edit.putString(KEY_PRIV_BLOB, wrapped.blob.toHex())
                .putString(KEY_PRIV_IV, wrapped.iv.toHex())
                .remove(KEY_PRIV_RAW)
        } else {
            // TEE unavailable (no Keystore / OEM quirk). Degrade rather than
            // brick the control channel: store the priv raw. A seized disk
            // image could then forge commands to this device's peers — a
            // real but device-specific weakness, mirrored on the seal path's
            // Model-A fallback. Logged so it's visible.
            Log.w(TAG, "no Keystore — control priv stored unwrapped (degraded)")
            edit.putString(KEY_PRIV_RAW, priv.toHex())
                .remove(KEY_PRIV_BLOB).remove(KEY_PRIV_IV)
        }
        edit.apply()
        cachedPriv = priv
    }

    private fun loadPriv(ctx: Context): ByteArray? {
        cachedPriv?.let { return it }
        ensureGenerated(ctx)
        cachedPriv?.let { return it }
        val p = prefs(ctx)
        val blobHex = p.getString(KEY_PRIV_BLOB, null)
        val priv = if (blobHex != null) {
            val iv = p.getString(KEY_PRIV_IV, null)?.hexToBytes() ?: return null
            SealKeyVault.unwrap(VAULT_TAG, blobHex.hexToBytes(), iv)
        } else {
            p.getString(KEY_PRIV_RAW, null)?.hexToBytes()
        }
        return priv?.also { cachedPriv = it }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
