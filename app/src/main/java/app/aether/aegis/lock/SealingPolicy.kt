package app.aether.aegis.lock

/**
 * Bridges the Repository / DAO layer to the PIN-keypair primitives
 * without dragging in Context. AegisApp constructs one of these wired
 * to the active profile's [LockStore] and the process-wide
 * [PinSession]; Repository calls [trySeal] on every insert and
 * [tryUnseal] on every read.
 *
 * No-op behaviour:
 *   - [trySeal] returns null when no PIN is configured (sealPub
 *     missing) — caller writes plaintext as fallback. This is the
 *     "user never set a PIN" path; nothing to encrypt against.
 *   - [tryUnseal] returns null when locked (no priv in session),
 *     when the sealed blob was generated against a different pub
 *     (PIN was rotated and the row hasn't been re-sealed yet), or
 *     when bytes are corrupted. Callers treat null as "show the
 *     locked placeholder", which is the right UX on every branch.
 */
class SealingPolicy(
    private val sealPubProvider: () -> ByteArray?,
    private val sealPrivProvider: () -> ByteArray?,
) {
    /** True iff a sealing keypair is configured for this profile. */
    val canSeal: Boolean
        get() = sealPubProvider() != null

    /** True iff [PinSession] currently holds a priv (REAL PIN entered
     *  this session). When false, [tryUnseal] always returns null. */
    val canUnseal: Boolean
        get() = sealPrivProvider() != null && sealPubProvider() != null

    fun trySeal(plaintext: ByteArray): ByteArray? {
        val pub = sealPubProvider() ?: return null
        return SealCrypto.seal(plaintext, pub)
    }

    fun tryUnseal(sealed: ByteArray): ByteArray? {
        val pub = sealPubProvider() ?: return null
        val priv = sealPrivProvider() ?: return null
        return SealCrypto.unseal(sealed, pub, priv)
    }

    companion object {
        /** No-op policy used for tests / pre-PIN bootstrap. Never seals,
         *  never unseals; rows pass through as plaintext. */
        val NOOP = SealingPolicy(sealPubProvider = { null }, sealPrivProvider = { null })
    }
}
