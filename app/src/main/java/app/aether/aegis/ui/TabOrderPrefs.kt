package app.aether.aegis.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted order of the four non-sos bottom-nav tabs. SOS stays
 * locked at index 2 of the final 5-tab list
 * (SOS position locked to center, non-movable) — only the other
 * four can move.
 *
 * Default order is a Fitts's-law derivation:
 *   Security | Settings | SOS | Chats | Radar
 * with the non-sos slots = ["security", "settings", "chats", "map"].
 *
 * Stored as a comma-separated string of route IDs in SharedPreferences.
 * Process-wide singleton StateFlow so the bottom nav re-renders the
 * moment Settings → Nav order toggles a swap.
 */
class TabOrderPrefs(context: Context) {

    private val prefs: SharedPreferences = singletonPrefs(context.applicationContext)

    /** Current order of the four non-sos tabs. Caller should inject
     *  "sos" at index 2 when composing the final 5-tab list. */
    val nonSOSOrder: StateFlow<List<String>> get() = _flow

    /** Canonical 5-tab order = nonSOS[0..1] + SOS + nonSOS[2..3].
     *  Mirrors AegisBottomNav's render order so the swipe-to-switch
     *  gesture lands on the same neighbour as the visual layout. */
    fun fullOrder(): List<String> {
        val n = _flow.value
        return buildList {
            addAll(n.take(2))
            add("sos")
            addAll(n.drop(2))
        }
    }

    /** Move the tab at [from] to [to] in the non-sos order. No-op if
     *  either index is out of range. Persists immediately and updates
     *  the flow so observers re-render. */
    @Synchronized
    fun move(from: Int, to: Int) {
        val cur = _flow.value.toMutableList()
        if (from !in cur.indices || to !in cur.indices || from == to) return
        val item = cur.removeAt(from)
        cur.add(to, item)
        persist(cur)
    }

    /** Reset to the default order. */
    fun reset() {
        persist(DEFAULT)
    }

    private fun persist(list: List<String>) {
        prefs.edit().putString(KEY_ORDER, list.joinToString(",")).apply()
        _flow.value = list
    }

    companion object {
        /** Default order — Settings | Security | SOS |
         *  Chats | Radar. Left → right = increasing urgency: boring →
         *  shield → emergency → daily use → locate. Security next to
         *  SOS so the two protection surfaces sit together; Settings
         *  exiled to the far left as least-used. */
        val DEFAULT = listOf("settings", "security", "chats", "map")

        private const val STORE_NAME = "aegis_tab_order"
        private const val KEY_ORDER = "non_sos_order"

        @Volatile private var prefsInstance: SharedPreferences? = null
        private fun singletonPrefs(ctx: Context): SharedPreferences =
            prefsInstance ?: synchronized(this) {
                prefsInstance ?: ctx.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE).also {
                    prefsInstance = it
                }
            }

        private val _flow by lazy {
            MutableStateFlow(DEFAULT)
        }

        /** Hydrate from disk on first access. We can't do this in the
         *  init block of the lazy delegate cleanly because we'd need
         *  a Context; safe to do here on the call into [TabOrderPrefs]. */
        private fun hydrate(prefs: SharedPreferences) {
            val raw = prefs.getString(KEY_ORDER, null) ?: return
            val parsed = raw.split(",").map { it.trim() }.filter { it in DEFAULT }
            // Reject mangled state (wrong size, missing routes) — fall
            // back to default so a corrupt pref doesn't half-hide tabs.
            if (parsed.size != DEFAULT.size || parsed.toSet() != DEFAULT.toSet()) return
            _flow.value = parsed
        }
    }

    init {
        hydrate(prefs)
    }
}
