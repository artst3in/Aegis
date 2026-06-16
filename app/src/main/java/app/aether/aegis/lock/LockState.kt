package app.aether.aegis.lock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Runtime gate that the Compose tree observes to decide whether to
 * render LockScreen or the real app.
 *
 *   - on app process start: locked iff a PIN is set
 *   - on backgrounding: stamp lastActiveAt
 *   - on resume: re-lock iff elapsed > LockStore.timeoutMs
 *   - on PIN success: unlock to layer N (0 = real, 1 = Fake #1, 2 = Fake #2)
 *
 * Three-layer duress scheme:
 *
 *   Layer 0  — real Aegis. Real chats, real family, real sos. Real
 *              unlock self-cleans the Layer-2 slot if anything wrote
 *              to it during the previous decoy session.
 *   Layer 1  — Fake #1, opened by the user-configured duress PIN.
 *              Settings expose the duress PIN panel; writes there
 *              persist to the Layer-2 slot — the attacker creates
 *              Fake #2 with their own hands.
 *   Layer 2  — Fake #2, opened by the attacker-configured PIN. Same
 *              decoy data; the duress panel shows "already configured"
 *              with no edit option. The attacker concludes Fake #1
 *              was the real profile.
 */
class LockState(context: Context) {

    val store = LockStore(context)

    /** One-shot: set when we just restarted out of an ephemeral profile (its
     *  lock wiped it + switched here). Forces the launch gate ON so the user
     *  re-authenticates and the seal loads, even if this profile has
     *  `requireAppLock = false` — without it the app came up unlocked-but-sealed
     *  (chats locked, no PIN prompt) after an ephemeral exit. Read-and-cleared
     *  here so it only applies to this one start. */
    private val forceLockThisStart: Boolean = runCatching {
        app.aether.aegis.profile.ProfileRegistry.get(context).consumeLockOnNextStart()
    }.getOrDefault(false)

    /** Compose-observable. UI reads this to decide which screen to show.
     *  Locked iff a PIN exists AND the owner has opted into app-launch
     *  PIN. Remote-access AUTH validates against the same PIN
     *  independently of this flag, so an owner who wants remote-only
     *  protection (no friction opening Aegis) can set
     *  `requireAppLock = false` and still have working remote auth. */
    var isLocked: Boolean by mutableStateOf(
        store.hasPin && (store.requireAppLock || forceLockThisStart),
    )
        private set

    /**
     * Which decoy layer the user is currently in.
     *   0 — real profile
     *   1 — Fake #1 (user-configured duress)
     *   2 — Fake #2 (attacker-configured duress)
     */
    var duressLayer: Int by mutableStateOf(0)
        private set

    /** Back-compat alias — true if we're in any decoy layer. */
    val inDuressMode: Boolean get() = duressLayer > 0

    /**
     * One-shot, in-memory "the next backgrounding is us, not the user
     * leaving" flag. Armed by [armPickerReturn] right before we launch a
     * system Activity that necessarily backgrounds Aegis but isn't the
     * owner walking away from their phone — the ringtone picker
     * (ContactDetailScreen), the SAF document picker (backup
     * export/import), the device-admin consent dialog, attachment
     * pickers. Consumed by the first [onForegrounded] after arming.
     *
     * Without it, a picker the user spends more than [LockStore.timeoutMs]
     * in (default 30 s — easy to exceed while auditioning ringtones or
     * browsing for a backup file) trips the idle relock and dumps them
     * back on the PIN screen mid-task — user-reported 2026-06-07
     * ("changing notification sound of a contact sends you out of the
     * app, forcing app lock"). Deliberately NOT persisted: if the process
     * dies while the picker is up, we WANT the relock on the way back in.
     * One-shot so a genuine later backgrounding still locks normally. */
    @Volatile private var suppressNextAutoLock = false

    /**
     * Arm the one-shot picker-return grace (see [suppressNextAutoLock]).
     * Call immediately before launching a system picker / consent dialog
     * from inside Aegis. Safe to call redundantly; it only ever sets the
     * flag, and the flag is cleared by the next foreground regardless.
     */
    fun armPickerReturn() {
        suppressNextAutoLock = true
    }

    /** Called from MainActivity.onStop — records when the user left. */
    fun onBackgrounded() {
        store.lastActiveAt = System.currentTimeMillis()
    }

    /** Called from MainActivity.onStart — relocks iff timeout elapsed. */
    fun onForegrounded() {
        if (!store.hasPin || !store.requireAppLock) {
            isLocked = false
            return
        }
        // Returning from an in-app system picker we launched ourselves —
        // not the user leaving. Consume the one-shot and restamp so the
        // idle clock restarts from now (the resumed session is "active"),
        // never relocking for this trip. See [suppressNextAutoLock].
        if (suppressNextAutoLock) {
            suppressNextAutoLock = false
            store.lastActiveAt = System.currentTimeMillis()
            return
        }
        val elapsed = System.currentTimeMillis() - store.lastActiveAt
        if (elapsed >= store.timeoutMs) {
            isLocked = true
            // The vault follows the app lock: a timeout relock shows the
            // lock screen but does NOT clear PinSession (so chats stay
            // instantly readable on a quick re-unlock), which means the
            // PinSession on-lock listener that drops the vault never
            // fires here. Drop it explicitly so an idle-timeout app lock
            // also seals the vault — "stays unlocked until the app is
            // locked".
            app.aether.aegis.vault.VaultSession.lock()
            maybeWipeEphemeralOnLock()
        }
    }

    /** PIN was set up — re-arm the gate so the user has to authenticate.
     *  Honors `requireAppLock`: if the owner has disabled the launch
     *  gate, lockNow() is a no-op (the PIN still gates remote-AUTH).
     *  Always wipes the in-memory PIN-derived priv so sealed chat
     *  rows become unreadable until the next REAL unlock. */
    fun lockNow() {
        PinSession.clear()
        if (store.hasPin && store.requireAppLock) isLocked = true
        maybeWipeEphemeralOnLock()
    }

    /**
     * Manual lock — the lock-curtain gesture. Unlike [lockNow] this
     * IGNORES `requireAppLock`:
     * the entire point is an on-demand lock the user can pull at any
     * moment "even with app lock disabled". Locks whenever a PIN exists
     * (so the next unlock goes through the PIN screen and duress applies);
     * a no-op if no PIN is set, since there'd be nothing to unlock with.
     */
    fun lockManual() {
        PinSession.clear()
        if (store.hasPin) isLocked = true
        maybeWipeEphemeralOnLock()
    }

    /**
     * If the active profile is ephemeral, locking
     * destroys it: schedule the wipe, switch to a surviving profile, and
     * restart into it. A no-op for normal profiles. Process death here is
     * intentional — the ephemeral profile must not survive a lock.
     */
    private fun maybeWipeEphemeralOnLock() {
        val scheduled = runCatching {
            app.aether.aegis.profile.EphemeralProfile
                .scheduleWipeIfEphemeral(app.aether.aegis.AegisApp.instance)
        }.getOrDefault(false)
        if (scheduled) android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Successful real-PIN match. Drops back to Layer 0 and — critically
     * — wipes the Layer-2 (attacker-created) slot so the next encounter
     * starts clean. This is the "self-cleaning" property of the
     * three-layer scheme. After this no trace exists that a second
     * decoy layer was ever there.
     */
    fun unlock() {
        store.resetAttempts()
        store.lastActiveAt = System.currentTimeMillis()
        store.clearDuress2Pin()
        isLocked = false
        duressLayer = 0
        // Sentinel watermark reset: a real unlock is the user's
        // acknowledgement gesture, so subsequent escalations can
        // ping again. Duress unlocks (unlockDuress1/2) deliberately
        // skip this — an attacker forcing the duress PIN must not
        // be able to silence future legit alerts.
        runCatching {
            app.aether.aegis.sentinel.SentinelState
                .engine(app.aether.aegis.AegisApp.instance).onUserUnlock()
        }
    }

    /**
     * REAL-PIN unlock that ALSO loads the phrase-rooted seal keypair into
     * [PinSession] (by unwrapping the TEE-stored priv) so sealed chat
     * content becomes readable. Use this from PIN-entry call sites; call
     * sites without a PIN (e.g. "Disable app lock" after clearPin) keep
     * using bare [unlock]. The seal is derived from the recovery phrase /
     * TEE, never the PIN, so the verified-PIN gate is the caller's job and
     * this takes no PIN argument.
     */
    fun unlockReal() {
        // Every profile is phrase-rooted: the seal keypair is derived from
        // the recovery phrase, NEVER the PIN. Under Model B (the default)
        // the priv sits wrapped by the device-bound TEE key, so a verified
        // REAL PIN is enough to unwrap it — no phrase entry for daily
        // unlock. If the unwrap fails (Model A, or a Keystore key
        // invalidated by a device change) kp stays null: the app still
        // opens, but sealed rows show the locked placeholder until the
        // user supplies the phrase via recovery.
        val kp = if (store.hasWrappedSealPriv) store.unwrapSealKeypair() else null
        if (kp != null) PinSession.set(kp)
        unlock()
    }

    /**
     * Install a seal keypair the caller derived out-of-band — used by
     * the recovery-phrase entry path (Model A boot unlock, or "Forgot
     * PIN → enter phrase"), where the priv comes from
     * [LockStore.deriveSealFromPhrase] rather than a TEE unwrap. Opens
     * the real profile exactly like [unlockReal]'s success branch.
     */
    fun unlockWithKeypair(kp: PinKeypair.KeyPair) {
        PinSession.set(kp)
        unlock()
    }

    /**
     * Successful duress-1 match (user's configured duress PIN).
     * Opens Fake #1. Silent sos is fired by the caller right after
     * this returns.
     */
    fun unlockDuress1() {
        store.resetAttempts()
        store.lastActiveAt = System.currentTimeMillis()
        isLocked = false
        duressLayer = 1
    }

    /**
     * Successful duress-2 match (attacker-set PIN). Opens Fake #2.
     * No sos fires — the attacker is testing, not coercing. The
     * second layer is purely for plausible-deniability theatre.
     */
    fun unlockDuress2() {
        store.resetAttempts()
        store.lastActiveAt = System.currentTimeMillis()
        isLocked = false
        duressLayer = 2
    }
}
