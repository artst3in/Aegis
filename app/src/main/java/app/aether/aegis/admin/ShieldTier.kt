package app.aether.aegis.admin

import android.content.Context
import androidx.compose.ui.graphics.Color
import app.aether.aegis.canary.CanaryStore
import app.aether.aegis.geofence.GeofenceStore
import app.aether.aegis.lock.LockStore
import app.aether.aegis.mugshot.MugshotStore
import app.aether.aegis.simswap.SimSwapStore
// AdminGate is in the same package, no explicit import needed.
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisGold
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisShieldBronze
import app.aether.aegis.ui.theme.AegisShieldSilver
import app.aether.aegis.vault.VaultLockStore

/**
 * Shield tier — the avatar frame around a member's identity.
 *
 * Linear, single-axis. Five tiers:
 *
 *   None    0 nodes  — nothing configured
 *   Bronze  1 node   — trunk set (App PIN)
 *   Silver  2-8      — working through features
 *   Gold    9        — every node but Device Owner
 *   Cyan    10       — Gold + Device Owner (the crown)
 *
 * Bronze → Silver → Gold is the standard medal climb. Cyan is the
 * brand colour and the highest honour: the person glowing cyan
 * wiped their phone, ran ADB, and maxed every node. Worn, not
 * defaulted.
 *
 * The 10 nodes counted are App PIN, App Duress, Mugshot, Vault PIN,
 * Vault Duress, Canary, Geofence, SIM Watch, Device Admin, and
 * Device Owner. (SOS Drill was spec-only and never wired — removed.)
 *
 * Device Owner promotion (via `dpm set-device-owner`) automatically
 * activates the Admin receiver, so reaching DO lights both the
 * Owner and Admin nodes at once — "two points for Owner". A user
 * who can only enrol Admin (Play Store install, no ADB) tops out
 * at Gold; Cyan requires actually achieving DO.
 */
enum class ShieldTier(val displayName: String) {
    None    ("None"),
    Bronze  ("Bronze"),
    Silver  ("Silver"),
    Gold    ("Gold"),
    Cyan    ("Cyan"),
    ;

    /** Frame colour for this tier — the accent used wherever the
     *  member's avatar is drawn (family dashboard, header badge,
     *  future profile-circle frames). [None] returns dim grey so
     *  the unrenowned-frame state still has a stroke. */
    fun color(): Color = when (this) {
        None    -> AegisOnSurfaceDim
        Bronze  -> AegisShieldBronze
        Silver  -> AegisShieldSilver
        Gold    -> AegisGold
        Cyan    -> AegisCyan
    }
}

object ShieldTierEngine {

    /** Maximum node count. 10 real nodes (SOS Drill was proposed but
     *  never wired, so it's removed). The 10: App PIN, App Duress,
     *  Mugshot, Vault PIN, Vault Duress, Canary, Geofence, SIM Watch,
     *  Device Admin, Device Owner. */
    const val MAX_NODES: Int = 10

    /** Map a node count to a tier. Pure function — same input always
     *  produces the same tier. Gold = everything but Device Owner (9);
     *  Cyan = the crown, all 10 incl. Device Owner. */
    fun tierFor(activeNodes: Int): ShieldTier = when {
        activeNodes <= 0  -> ShieldTier.None
        activeNodes == 1  -> ShieldTier.Bronze
        activeNodes <= 8  -> ShieldTier.Silver
        activeNodes == 9  -> ShieldTier.Gold
        else              -> ShieldTier.Cyan
    }

    /**
     * Live count of lit nodes for the current profile. Reads every
     * relevant store directly — each one resolves to a small
     * SharedPreferences lookup that's already cached after first
     * access, so this is cheap enough to call from any composable
     * without memoisation.
     */
    fun activeNodes(context: Context): Int {
        val lock = LockStore(context)
        val vault = VaultLockStore(context)
        var count = 0
        if (lock.hasPin)                                    count++ // App PIN
        if (lock.hasDuressPin)                              count++ // App Duress
        if (MugshotStore(context).enabled)                  count++ // Mugshot
        if (vault.hasPin)                                   count++ // Vault PIN
        if (vault.hasDuressPin)                             count++ // Vault Duress
        if (CanaryStore(context).enabled)                   count++ // Canary
        if (GeofenceStore(context).enabled)                 count++ // Geofence
        if (SimSwapStore(context).enabled)                  count++ // SIM Watch
        // Device Admin — true if the in-app enrollment was accepted
        // OR if Device Owner was set (DO promotion auto-activates
        // the admin receiver). The "or" is what produces "two
        // points for Owner": going DO lights both this counter
        // AND the DO counter below.
        if (AdminGate.isActive(context))                    count++ // Device Admin
        if (DeviceOwnerStatus.isActive(context))            count++ // Device Owner
        return count
    }

    /** Snapshot of the current shield tier for this profile. */
    fun currentTier(context: Context): ShieldTier {
        return tierFor(activeNodes(context))
    }
}
