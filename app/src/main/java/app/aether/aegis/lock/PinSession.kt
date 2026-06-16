package app.aether.aegis.lock

/**
 * Process-wide handle to the in-memory PIN-derived private key.
 *
 * Set on successful REAL-PIN unlock; cleared on lock or process death.
 * Duress unlocks do NOT populate it — sealed content stays
 * inaccessible in fake mode, which is the right UX (the attacker
 * sees no chat history at all in the decoy).
 *
 * The public key counterpart lives in [LockStore.sealPub] so the
 * background receive pipeline can encrypt without this session being
 * active.
 */
object PinSession {
    @Volatile
    private var keypair: PinKeypair.KeyPair? = null

    private val onLockListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Compose-observable view of [isUnlocked]. Banners / gated UI
     *  elements collect this so they re-render the moment [set] or
     *  [clear] flips the state — no manual tick needed. */
    private val _isUnlockedFlow =
        kotlinx.coroutines.flow.MutableStateFlow(false)
    val isUnlockedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isUnlockedFlow

    /** True iff REAL PIN has been entered this session and the priv
     *  is in memory. */
    val isUnlocked: Boolean
        get() = keypair != null

    /** Snapshot the priv for a decrypt operation. Returns null when
     *  locked. The returned ByteArray is the live session copy —
     *  callers MUST NOT wipe it. */
    fun priv(): ByteArray? = keypair?.priv

    /** Snapshot the cached pub — convenience for symmetry with [priv]
     *  in the unsealing helper. */
    fun pub(): ByteArray? = keypair?.pub

    /** Install a freshly-derived keypair (REAL unlock only). Replaces
     *  any prior session, wiping the old priv. */
    fun set(kp: PinKeypair.KeyPair) {
        val prior = keypair
        keypair = kp
        prior?.wipe()
        _isUnlockedFlow.value = true
    }

    /** Wipe the priv from memory and fire every registered on-lock
     *  callback. Called from [LockState.lockNow] and on process
     *  backgrounding flows that require relock. */
    fun clear() {
        val prior = keypair
        keypair = null
        prior?.wipe()
        _isUnlockedFlow.value = false
        onLockListeners.forEach { runCatching { it() } }
    }

    /** Register a hook to run synchronously on every [clear] / lock
     *  event. Used by [ChatAttachmentSeal] to wipe decrypted scratch
     *  files the moment the user re-locks. AegisApp registers these
     *  at process start; never unregistered (process lifetime). */
    fun addOnLockListener(listener: () -> Unit) {
        onLockListeners += listener
    }
}
