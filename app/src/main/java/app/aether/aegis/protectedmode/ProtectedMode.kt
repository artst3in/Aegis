package app.aether.aegis.protectedmode

import android.content.Context
import android.content.SharedPreferences
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Protected Mode — a configurable, PIN-guarded lock over destructive /
 * escalating actions, so a child on their own phone (or a user putting a
 * Ulysses pact between themselves and an impulse) can't accidentally cut
 * their own safety net.
 *
 * **This is NOT a security feature.** Every other lock in Aegis (app PIN,
 * duress PIN, vault PIN) defends against an *adversary*. Protected Mode
 * defends against the everyday user's own fingers. Whoever holds the
 * protected-mode PIN can disable it; that's by design — there is no
 * hostile party to keep it from. The friction *is* the feature: enter
 * PIN → do the thing → re-arm.
 *
 * Hard invariants (see docs/SPEC_PROTECTED_MODE.md):
 *  1. SOS is NEVER gated — not a gate in [Gate], full stop.
 *  2. The owner is never locked out — the app PIN is always a master
 *     override for disarming (enforced at the disarm call sites).
 *  3. Inert under duress — [isGated] returns false in any decoy layer,
 *     so the mode never interacts with plausible deniability.
 *  4. Off by default.
 *
 * Process-wide singleton backed by per-profile [ProtectedModeStore].
 * Hydrated lazily on first access ([boot]); a profile switch restarts the
 * process, so per-process hydration is always correct for the active
 * profile.
 */
object ProtectedMode {

    /**
     * The à-la-carte gates. Each is an independent toggle the arming user
     * ticks. **SOS is deliberately absent** (invariant 1). Adding a gate
     * here is the whole extension point — define it, surface it in the
     * config screen, and guard the matching call site with [isGated].
     */
    enum class Gate {
        /** The Settings ("System") nav tab — locks in place, dimmed. */
        SYSTEM_TAB,
        /** The Security ("Opsec") nav tab — locks in place, dimmed. */
        OPSEC_TAB,
        /** Add / delete contacts. */
        CONTACTS,
        /** Promote / demote trust tier (the silent-SOS-break). */
        TRUST_TIER,
        /** Create / join / leave groups. */
        GROUPS,
        /** Remove the Groups sub-tab entirely. */
        HIDE_GROUPS,
        /** Wipe-all / core reset / the destructive ops. */
        DANGER_ZONE,
        /** Switch / delete profile. */
        PROFILES,
        /** The self-update controls. */
        UPDATES,
    }

    /**
     * Curated starting points. Presets are only *starting points* — flip
     * one toggle after picking one and you're in "Custom (from preset)".
     */
    enum class Preset(val gates: Set<Gate>) {
        /** A kid's own phone: everything destructive, Groups still visible. */
        CHILD(
            setOf(
                Gate.SYSTEM_TAB, Gate.OPSEC_TAB, Gate.CONTACTS, Gate.TRUST_TIER,
                Gate.GROUPS, Gate.DANGER_ZONE, Gate.PROFILES, Gate.UPDATES,
            ),
        ),

        /** "Nothing but chats, map, and SOS" — Child plus Hide Groups. */
        LOCKDOWN(CHILD.gates + Gate.HIDE_GROUPS),
    }

    /**
     * Gates whose enforcement is actually wired in this build. The config
     * UI offers ONLY these, so a toggle never promises protection that
     * isn't there. To add a new [Gate], guard its call site with [isGated]
     * and list it here. All current gates are wired.
     */
    val WIRED: Set<Gate> = setOf(
        Gate.SYSTEM_TAB, Gate.OPSEC_TAB, Gate.CONTACTS, Gate.TRUST_TIER,
        Gate.GROUPS, Gate.HIDE_GROUPS, Gate.DANGER_ZONE, Gate.PROFILES,
        Gate.UPDATES,
    )

    private val _armed = MutableStateFlow(false)

    /** True iff Protected Mode is currently armed. Compose-observable. */
    val armed: StateFlow<Boolean> get() = _armed

    private val _gates = MutableStateFlow<Set<Gate>>(emptySet())

    /** The set of gates the arming user selected. Compose-observable. */
    val gates: StateFlow<Set<Gate>> get() = _gates

    @Volatile private var store: ProtectedModeStore? = null

    /** Idempotent hydrate from the active-profile store. Safe to call from
     *  any thread; the first call wins and seeds the flows. */
    private fun boot(): ProtectedModeStore {
        store?.let { return it }
        return synchronized(this) {
            store ?: run {
                val s = ProtectedModeStore(app.aether.aegis.AegisApp.instance)
                _armed.value = s.armed
                _gates.value = s.gates
                store = s
                s
            }
        }
    }

    /**
     * Whether [gate] is currently blocked. The single predicate every
     * guarded call site / UI control reads.
     *
     * Returns false (open) when: not armed, the gate wasn't selected, OR
     * we're in any decoy layer (invariant 3 — Protected Mode must never
     * leak into the duress world or interact with deniability).
     */
    fun isGated(gate: Gate): Boolean {
        boot()
        if (!_armed.value) return false
        if (gate !in _gates.value) return false
        // Invariant 3: inert under duress. A decoy session behaves as if
        // Protected Mode does not exist.
        val inDecoy = runCatching {
            app.aether.aegis.AegisApp.instance.lockState.inDuressMode
        }.getOrDefault(false)
        if (inDecoy) return false
        return true
    }

    /** True once a protected-mode PIN has been set. */
    fun hasPin(): Boolean = boot().hasPin

    /** Set (or replace) the protected-mode PIN. Does not arm. */
    fun setPin(pin: String) {
        boot().setPin(pin)
    }

    /**
     * Verify [pin] against the protected-mode PIN only. The app-PIN master
     * override (invariant 2) is checked separately at the disarm call
     * sites — it is intentionally NOT folded in here so this stays a pure
     * "is this the protected PIN" check.
     */
    fun verify(pin: String): Boolean = boot().verifyPin(pin)

    /**
     * Arm with the given gate set. Requires a PIN to already exist; arming
     * without one would be a lock nobody can open through the intended
     * disarm path (the app-PIN override still works, but that's the escape
     * hatch, not the front door). No-op if no PIN is set.
     */
    fun arm(selected: Set<Gate>) {
        val s = boot()
        if (!s.hasPin) return
        s.gates = selected
        s.armed = true
        _gates.value = selected
        _armed.value = true
    }

    /** Disarm. Callers gate this behind a verified PIN (protected or app). */
    fun disarm() {
        val s = boot()
        s.armed = false
        _armed.value = false
    }
}

/**
 * Per-profile persistence for Protected Mode. Plain SharedPreferences —
 * same boundary as [app.aether.aegis.lock.LockStore]. The PIN is hashed
 * with Argon2id (libsodium INTERACTIVE, salt baked into the encoded
 * string) exactly like the app PIN; no separate salt column and no KDF
 * use — this PIN only ever gates a UI state, it never derives a key.
 */
class ProtectedModeStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val name = app.aether.aegis.profile.ProfileRegistry
            .get(context).current.prefsName(STORE_NAME)
        context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
    private val sodium = app.aether.aegis.crypto.Sodium.shared

    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) { prefs.edit().putBoolean(KEY_ARMED, value).apply() }

    /** The selected gates, persisted as their enum names. Unknown names
     *  (e.g. a gate removed in a later build) are skipped on read. */
    var gates: Set<ProtectedMode.Gate>
        get() = prefs.getStringSet(KEY_GATES, emptySet()).orEmpty()
            .mapNotNull { name ->
                runCatching { ProtectedMode.Gate.valueOf(name) }.getOrNull()
            }
            .toSet()
        set(value) {
            prefs.edit()
                .putStringSet(KEY_GATES, value.map { it.name }.toSet())
                .apply()
        }

    val hasPin: Boolean get() = prefs.getString(KEY_PIN_HASH, null) != null

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return runCatching { sodium.cryptoPwHashStrVerify(hash, pin) }
            .getOrDefault(false)
    }

    private fun hashPin(pin: String): String =
        runCatching {
            sodium.cryptoPwHashStr(
                pin,
                PwHash.OPSLIMIT_INTERACTIVE.toLong(),
                PwHash.MEMLIMIT_INTERACTIVE,
            )
        }.getOrElse { error("argon2id hash failed: $it") }

    companion object {
        private const val STORE_NAME = "aegis_protected_mode"
        private const val KEY_PIN_HASH = "protected_pin_hash"
        private const val KEY_ARMED = "protected_armed"
        private const val KEY_GATES = "protected_gates"
    }
}
