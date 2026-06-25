package app.aether.aegis.admin

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.util.Log

/**
 * Self-grant of every permission Aegis can claim when it's Device
 * Owner. Covers four protection layers:
 *
 *   1. Dangerous runtime permissions
 *      via [DevicePolicyManager.setPermissionGrantState] — flips to
 *      GRANTED and pins the Settings toggle so the owner can't
 *      half-revoke. This is the bulk of what the remote-access
 *      surface (LOCATE / LISTEN / DISPLAY / mugshot) depends on.
 *
 *   2. AppOp-class permissions (REQUEST_INSTALL_PACKAGES,
 *      SYSTEM_ALERT_WINDOW)
 *      via [AppOpsManager.setMode] called through reflection. The
 *      public API requires MANAGE_APP_OPS_MODES (signature-only),
 *      but on DO devices the syscall is permitted because Aegis is
 *      effectively a system app. Best-effort — fails silently on
 *      OEMs that lock it down.
 *
 *   3. Blocking user restrictions
 *      via [DevicePolicyManager.clearUserRestriction]. If a previous
 *      provisioning step (or the owner experimenting in dpm shell)
 *      left a DISALLOW_* restriction in place that would block
 *      Aegis features (camera, install, debugging, location config),
 *      we lift it.
 *
 *   4. Future-proof grant policy
 *      via [DevicePolicyManager.setPermissionPolicy] with
 *      PERMISSION_POLICY_AUTO_GRANT — any *new* runtime permission
 *      Aegis adds in a future release is auto-granted on first
 *      request without firing the runtime dialog. This is the
 *      catch-all: even if we forget to add a new perm to the
 *      explicit list below, it grants itself the moment Aegis asks.
 *
 *   5. Battery optimization whitelist
 *      via [PowerManager.isIgnoringBatteryOptimizations] check +
 *      reflective ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS as
 *      a fallback. As DO we don't get the auto-whitelist that
 *      managed-system apps do, so we explicitly request inclusion.
 *
 * Everything no-ops when Aegis isn't Device Owner.
 */
object PermissionAutoGrant {

    private const val TAG = "PermissionAutoGrant"

    /** Dangerous runtime permissions Aegis declares + uses. Kept in
     *  sync with AndroidManifest.xml. Normal-protection permissions
     *  (INTERNET, WAKE_LOCK, VIBRATE, FOREGROUND_SERVICE*) are already
     *  granted at install time, so they're not listed. */
    private val DANGEROUS_PERMS: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        @Suppress("DEPRECATION")
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }

    /** AppOp string + a label for logging. Each is granted via
     *  AppOpsManager.setMode reflection; the public API requires
     *  signature-only privilege but DO traffic can sneak through on
     *  many stock builds. Failures are logged not surfaced.
     *
     *  OPSTR_REQUEST_INSTALL_PACKAGES isn't in the public SDK on
     *  every API level, so the string is hardcoded — it's an Android
     *  stable identifier ("android:request_install_packages"). */
    private val APPOPS: List<Pair<String, String>> = listOf(
        "android:request_install_packages" to "REQUEST_INSTALL_PACKAGES",
        AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW to "SYSTEM_ALERT_WINDOW",
    )

    /** User restrictions that would otherwise block Aegis features.
     *  Cleared (not set) — we don't want any DO-applied DISALLOW
     *  lingering from a previous experiment / provisioning step. */
    private val RESTRICTIONS_TO_CLEAR: List<String> = listOf(
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_INSTALL_APPS,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_CONFIG_LOCATION,
        UserManager.DISALLOW_SHARE_LOCATION,
        UserManager.DISALLOW_CONFIG_BLUETOOTH,
        UserManager.DISALLOW_BLUETOOTH,
        // No DISALLOW_CAMERA on UserManager — camera control is via
        // DPM.setCameraDisabled, handled separately in ensureCameraEnabled.
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_ADJUST_VOLUME,
        UserManager.DISALLOW_OUTGOING_CALLS,
    )

    /** Top-level pass run from AegisApp.onCreate. Only the dangerous
     *  runtime perm grant fires unconditionally — the broader
     *  operations (setPermissionPolicy AUTO_GRANT, AppOps reflection,
     *  user-restriction cleanup, setCameraDisabled, battery
     *  whitelist intent) are gated behind a manual opt-in flag.
     *
     *  Why gated: 2026.05.674 shipped them globally and Pixel
     *  devices regressed — empty chat bodies, no GPS fix, general
     *  data-flow failure. OnePlus (same APK) ran clean, which
     *  pointed to a Pixel-specific interaction with one of the
     *  extra operations. Rolled them out of the cold-start path
     *  pending bisection; the methods stay available so the deeper
     *  pass can be re-enabled per-device or behind a debug toggle. */
    fun tryGrantAll(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? DevicePolicyManager ?: return
        if (!runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)) {
            // Log.d(TAG, "skip: not Device Owner")
            return
        }
        val admin = AdminGate.component(context)
        // One-shot reset of any AUTO_GRANT policy that 2026.05.674 may
        // have set. Sticky in OS state — we only need to write PROMPT
        // once per install to undo it; calling every cold start is
        // wasted DPM IPC.
        val prefs = context.getSharedPreferences("aegis_perms", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("policy_reset_to_prompt", false)) {
            runCatching {
                dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_PROMPT)
                prefs.edit().putBoolean("policy_reset_to_prompt", true).apply()
                Log.i(TAG, "reset setPermissionPolicy → PROMPT (one-shot)")
            }.onFailure { Log.w(TAG, "reset PROMPT failed: ${it.message}") }
        }
        grantDangerous(context, dpm, admin)
        clearBlockingRestrictions(dpm, admin)
        // Full 2026.05.674 stack re-enabled. The Pixel heat
        // regression turned out to be the 15-min auto-update loop
        // (download + install + process restart every tick), not
        // anything in PermissionAutoGrant — the throttle in
        // 2026.05.687 fixed it. Bringing the wider ops back gets us
        // genuine "Aegis self-provisions everything" behavior.
        setAutoGrantPolicy(dpm, admin)
        grantAppOps(context)
        ensureCameraEnabled(dpm, admin)
        whitelistBatteryOptimizations(context)
    }

    private fun grantDangerous(
        context: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
    ) {
        var granted = 0
        var already = 0
        var failed = 0
        for (perm in DANGEROUS_PERMS) {
            val cur = runCatching { dpm.getPermissionGrantState(admin, context.packageName, perm) }
                .getOrDefault(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
            if (cur == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                already++
                continue
            }
            val ok = runCatching {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.getOrDefault(false)
            if (ok) {
                granted++
                Log.i(TAG, "granted runtime $perm")
            } else {
                failed++
                Log.w(TAG, "failed to grant runtime $perm")
            }
        }
        Log.i(TAG, "runtime: $granted granted, $already already, $failed failed")
    }

    /** Tells the system: "any future runtime perm Aegis asks for,
     *  just give it without showing the dialog." Insurance against
     *  us adding a new uses-permission tag and forgetting to update
     *  [DANGEROUS_PERMS]. */
    private fun setAutoGrantPolicy(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
    ) {
        runCatching {
            dpm.setPermissionPolicy(admin, DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT)
            Log.i(TAG, "setPermissionPolicy = AUTO_GRANT")
        }.onFailure {
            Log.w(TAG, "setPermissionPolicy failed: ${it.message}")
        }
    }

    /** AppOps grant via reflection on AppOpsManager.setMode(String,
     *  int, String, int). The Pixel/Android-12+ shape uses setMode
     *  with op-string, int uid, String packageName, int mode. */
    private fun grantAppOps(context: Context) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as? AppOpsManager ?: return
        val uid = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).uid
        }.getOrNull() ?: return
        for ((op, label) in APPOPS) {
            val ok = runCatching {
                // AppOpsManager.setMode(String op, int uid, String packageName, int mode)
                // Public API requires MANAGE_APP_OPS_MODES — signature-only — but DO
                // calls have historically slipped through on stock Android. Reflective
                // because the signature isn't in the SDK.
                val m = AppOpsManager::class.java.getMethod(
                    "setMode",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                m.invoke(appOps, op, uid, context.packageName, AppOpsManager.MODE_ALLOWED)
                true
            }.getOrElse {
                Log.w(TAG, "appop $label setMode failed: ${it.message}")
                false
            }
            if (ok) Log.i(TAG, "granted appop $label")
        }
    }

    private fun clearBlockingRestrictions(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
    ) {
        var cleared = 0
        for (r in RESTRICTIONS_TO_CLEAR) {
            runCatching {
                dpm.clearUserRestriction(admin, r)
                cleared++
            }.onFailure {
                // Most clear-on-unset throws — that's the no-op case,
                // not a real failure. Log at debug.
                // Log.d(TAG, "clearUserRestriction $r: ${it.message}")
            }
        }
        Log.i(TAG, "cleared $cleared user restrictions")
    }

    /** Some provisioning flows set DO-level CameraDisabled=true as
     *  a starting policy. We need the camera for mugshot capture +
     *  remote LISTEN/DISPLAY companion shots; explicitly clear it. */
    private fun ensureCameraEnabled(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
    ) {
        runCatching {
            if (dpm.getCameraDisabled(admin)) {
                dpm.setCameraDisabled(admin, false)
                Log.i(TAG, "cleared DO setCameraDisabled")
            }
        }.onFailure { Log.w(TAG, "ensureCameraEnabled failed: ${it.message}") }
    }

    /** Battery optimizations whitelist. No DO API for this — the
     *  user-facing intent is the only public path. We fire the system
     *  request dialog once per install (gated by a SharedPreferences
     *  flag) so a one-tap confirmation lands the package on the
     *  whitelist forever. If user dismisses, DiagnosticsScreen exposes
     *  a manual re-trigger; we don't keep popping the dialog on every
     *  cold start. */
    private fun whitelistBatteryOptimizations(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val whitelisted = runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
        if (whitelisted) {
            // Log.d(TAG, "battery: already ignoring optimizations")
            return
        }
        val prefs = context.getSharedPreferences("aegis_perms", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_whitelist_requested", false)) {
            // Log.d(TAG, "battery: prompt already shown this install")
            return
        }
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            ).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            prefs.edit().putBoolean("battery_whitelist_requested", true).apply()
            Log.i(TAG, "battery: launched whitelist request (one-shot)")
        }.onFailure { Log.w(TAG, "battery whitelist intent failed: ${it.message}") }
    }
}
