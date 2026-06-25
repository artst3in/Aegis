package app.aether.aegis.groups

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Master gate for the group-chat module. Defaults to **off** on
 * fresh install — the threat model treats public group
 * code as additional attack surface that the user must
 * deliberately opt into, every time.
 *
 * Phase 1 (this build): a runtime gate that
 *   - hides the existing Groups list when off
 *   - blocks inbound SimpleX group dispatch when off
 *   - blocks outbound group sends when off
 *   - surfaces an "Enable Group Chat" warning + confirm dialog
 *     on every enable (no "don't show again")
 *   - flips frictionlessly on disable
 *
 * Phase 2 (separate work): extract the group code into a
 * standalone `:feature:groups` Gradle module so the boundary is
 * compile-time enforced rather than runtime-gated.
 *
 * Process-wide singleton StateFlow so Compose UIs + the SimpleX
 * dispatcher observe the same source of truth.
 */
class GroupModulePrefs(context: Context) {

    private val prefs: SharedPreferences = profileScopedPrefs(context.applicationContext)

    /** Master on/off. Group code paths consult this on every
     *  inbound dispatch and every outbound send. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            _enabledFlow.value = value
        }

    val enabledFlow: StateFlow<Boolean> get() = _enabledFlow

    /** Whether the dead-man's-switch timer is armed. Optional —
     *  off by default. When on, the module auto-disables itself
     *  N minutes after
     *  the user last exited the Groups tab. If you don't actively
     *  engage with groups, the system assumes you're done and
     *  locks down the attack surface. */
    var autoDisableEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISABLE_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DISABLE_ENABLED, value).apply()
            _autoDisableEnabledFlow.value = value
        }

    val autoDisableEnabledFlow: StateFlow<Boolean> get() = _autoDisableEnabledFlow

    /** Auto-disable inactivity window in minutes. Default 30. */
    var autoDisableMinutes: Int
        get() = prefs.getInt(KEY_AUTO_DISABLE_MINUTES, DEFAULT_AUTO_DISABLE_MINUTES)
        set(value) {
            prefs.edit().putInt(KEY_AUTO_DISABLE_MINUTES, value.coerceAtLeast(1)).apply()
            _autoDisableMinutesFlow.value = value.coerceAtLeast(1)
        }

    val autoDisableMinutesFlow: StateFlow<Int> get() = _autoDisableMinutesFlow

    companion object {
        /** Off by default: fresh install = zero group attack
         *  surface until the user opts in. */
        private const val DEFAULT_ENABLED = false

        private const val DEFAULT_AUTO_DISABLE_MINUTES = 30
        private const val STORE_NAME = "aegis_group_module"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_DISABLE_ENABLED = "auto_disable_enabled"
        private const val KEY_AUTO_DISABLE_MINUTES = "auto_disable_minutes"

        @Volatile private var prefsInstance: SharedPreferences? = null
        /** Per-profile scoping — different Aegis profiles maintain
         *  independent enable/disable state. Profile id is supplied
         *  by the app via
         *  [GroupModuleHost.activeProfileId]; if the host isn't
         *  installed yet (very-early worker invocations) we fall
         *  back to the legacy unsuffixed store so pre-profile-
         *  scoping data isn't lost. Profile switches kill the
         *  process so the static cache below holds at most one
         *  profile's prefs per process lifetime — safe to memoise. */
        private fun profileScopedPrefs(ctx: Context): SharedPreferences =
            prefsInstance ?: synchronized(this) {
                prefsInstance ?: run {
                    val profileId = GroupModuleHostHolder.current?.activeProfileId()
                    val storeName = if (profileId != null) "${STORE_NAME}_$profileId"
                                    else STORE_NAME
                    ctx.getSharedPreferences(storeName, Context.MODE_PRIVATE).also {
                        prefsInstance = it
                    }
                }
            }

        private val _enabledFlow by lazy { MutableStateFlow(DEFAULT_ENABLED) }
        private val _autoDisableEnabledFlow by lazy { MutableStateFlow(false) }
        private val _autoDisableMinutesFlow by lazy { MutableStateFlow(DEFAULT_AUTO_DISABLE_MINUTES) }

        /** Allow non-UI call sites (SimpleXTransport dispatch
         *  loop) to read the current state without spinning up a
         *  full prefs instance per check. The flow value is the
         *  single source of truth; the SharedPreferences row is
         *  just the durable backing. */
        fun currentEnabled(): Boolean = _enabledFlow.value

        /** Process-wide enabled state as an observable flow, for non-UI
         *  reactive consumers (e.g. the unread-dot tracker) that must stop
         *  surfacing group state the instant the module is toggled off.
         *  Same single source of truth as [currentEnabled]. */
        fun enabledFlowStatic(): StateFlow<Boolean> = _enabledFlow

        /** Snapshot for the WorkManager worker. */
        fun currentAutoDisableEnabled(): Boolean = _autoDisableEnabledFlow.value
        fun currentAutoDisableMinutes(): Int = _autoDisableMinutesFlow.value
    }

    init {
        _enabledFlow.value = enabled
        _autoDisableEnabledFlow.value = autoDisableEnabled
        _autoDisableMinutesFlow.value = autoDisableMinutes
    }
}
