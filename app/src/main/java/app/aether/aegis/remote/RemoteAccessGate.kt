package app.aether.aegis.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * Target-side gate for remote-access commands.
 *
 *  - PIN-failure counter per sender. Auto-revokes after [FAIL_THRESHOLD]
 *    failures within [FAIL_WINDOW_MS]. The threshold is deliberately
 *    low (3 in 60 s) so a coerced user can sabotage themselves by
 *    typing wrong PINs and forcing their target to lock them out.
 *  - Persistent revoke set (sender pubkeys → "I have blocked them").
 *    Future auth attempts from a revoked sender are dropped server-
 *    side without notifying the sender (and without re-prompting US).
 *  - In-memory session map. A session is opened on AUTH_OK with a
 *    random sid, expires [SESSION_TTL_MS] after the last command.
 *    Sender attaches sid to subsequent LOCATE/SIREN/WIPE packets;
 *    we look it up to authorise without re-prompting.
 *
 * All state lives outside the SimpleX core so reboots / app kills don't
 * leak active sessions. Persisted state (the revoke set) lives in a
 * single SharedPreferences file.
 */
class RemoteAccessGate(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("remote_access_gate", Context.MODE_PRIVATE)

    // ---- Revoked-senders set (persistent) ----

    private val _revoked = MutableStateFlow(loadRevoked())
    val revoked: StateFlow<Set<String>> = _revoked.asStateFlow()

    fun isRevoked(senderKey: String): Boolean = senderKey in _revoked.value

    fun revoke(senderKey: String) {
        val next = _revoked.value + senderKey
        if (next == _revoked.value) return
        prefs.edit().putStringSet(KEY_REVOKED, next).apply()
        _revoked.value = next
        // Killing the revoke flag also drops any live session for that
        // sender — otherwise the sender's outstanding sid would keep
        // working until idle timeout.
        synchronized(sessions) {
            sessions.entries.removeAll { it.value.sender == senderKey }
        }
    }

    fun unrevoke(senderKey: String) {
        val next = _revoked.value - senderKey
        if (next == _revoked.value) return
        prefs.edit().putStringSet(KEY_REVOKED, next).apply()
        _revoked.value = next
    }

    private fun loadRevoked(): Set<String> =
        prefs.getStringSet(KEY_REVOKED, null)?.toSet() ?: emptySet()

    // ---- Failed-PIN tracker (in-memory) ----

    /** Per-sender list of failure timestamps within the window. */
    private val failures = mutableMapOf<String, MutableList<Long>>()

    /**
     * Record a failed auth. Returns true if the threshold was tripped
     * by this call (caller should auto-revoke and notify the target).
     * Returns false when the sender is already revoked — duplicates
     * are silently absorbed so a persistent attacker can't spam the
     * notification surface.
     */
    @Synchronized
    fun recordFailure(senderKey: String): Boolean {
        if (isRevoked(senderKey)) return false
        val now = System.currentTimeMillis()
        val cutoff = now - FAIL_WINDOW_MS
        val list = failures.getOrPut(senderKey) { mutableListOf() }
        list.removeAll { it < cutoff }
        list += now
        return if (list.size >= FAIL_THRESHOLD) {
            failures.remove(senderKey)
            revoke(senderKey)
            true
        } else false
    }

    @Synchronized
    fun clearFailures(senderKey: String) {
        failures.remove(senderKey)
    }

    // ---- Session map (in-memory) ----

    private data class Session(val sender: String, var expiry: Long)

    private val sessions = mutableMapOf<String, Session>()

    /** Open a fresh session for [senderKey], returning the new sid. */
    @Synchronized
    fun openSession(senderKey: String): String {
        sweepExpired()
        val sid = newSid()
        sessions[sid] = Session(senderKey, System.currentTimeMillis() + SESSION_TTL_MS)
        return sid
    }

    /**
     * Look up an active session by [sid]. Returns the matching sender's
     * pubkey on hit (and bumps the expiry), null on miss / expiry /
     * mismatch. Returns null if the session belongs to a revoked
     * sender — defence-in-depth against a stale sid lingering after
     * revocation.
     */
    @Synchronized
    fun validateSession(sid: String): String? {
        sweepExpired()
        val s = sessions[sid] ?: return null
        if (isRevoked(s.sender)) {
            sessions.remove(sid)
            return null
        }
        s.expiry = System.currentTimeMillis() + SESSION_TTL_MS
        return s.sender
    }

    @Synchronized
    fun closeSession(sid: String) {
        sessions.remove(sid)
    }

    @Synchronized
    fun closeAllForSender(senderKey: String) {
        sessions.entries.removeAll { it.value.sender == senderKey }
    }

    /** True iff at least one non-expired session exists for the given
     *  sender pubkey. Used by [RemoteWatchMode] to decide whether to
     *  keep ticking; bumping expiry via this lookup is intentional —
     *  watch traffic effectively keeps the session warm as long as
     *  the sender's UI is on screen. */
    @Synchronized
    fun peerHasActiveSession(senderKey: String): Boolean {
        sweepExpired()
        val match = sessions.values.firstOrNull { it.sender == senderKey } ?: return false
        if (isRevoked(senderKey)) return false
        match.expiry = System.currentTimeMillis() + SESSION_TTL_MS
        return true
    }

    private fun sweepExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value.expiry < now }
    }

    private fun newSid(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
    }

    companion object {
        const val FAIL_THRESHOLD = 3
        const val FAIL_WINDOW_MS = 60_000L
        const val SESSION_TTL_MS = 5L * 60_000L  // 5 min idle

        private const val KEY_REVOKED = "revoked_senders"
    }
}
