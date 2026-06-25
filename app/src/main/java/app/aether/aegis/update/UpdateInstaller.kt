package app.aether.aegis.update

import app.aether.aegis.AegisApp
import app.aether.aegis.MainActivity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the Android PackageInstaller to install the just-downloaded APK.
 *
 * Green: opens the standard system install prompt (user taps Install).
 * Red:   silent install via PackageInstaller's session API. Requires the
 *        app to be Device Owner; otherwise falls back to Green path.
 *
 * A copy of the currently-installed APK is preserved alongside as
 * `previous.apk` for the A/B rollback path (see BootHealthMonitor).
 */
object UpdateInstaller {

    /**
     * `foreground=true`: launch the system install prompt directly via
     * ACTION_VIEW. Use when the user is actively in the app (Settings
     * Check now, in-app banner). No notification middle-step.
     * `foreground=false`: post a "tap to install" notification.
     * Used by the background worker so we don't yank the user into
     * PackageInstaller while they're using a different app.
     * `silent=true` on Red Device Owner: PackageInstaller session with
     * USER_ACTION_NOT_REQUIRED — no UI at all.
     * `stashPrevious=true` is the normal forward-install path —
     * the currently-installed APK is copied to previous.apk so
     * the next rollback has something to fall back to. ROLLBACK
     * call sites MUST pass `stashPrevious=false`, otherwise the
     * stash overwrites previous.apk with the *current* APK before
     * the rollback fires and we end up reinstalling the current
     * version on top of itself — silently doing nothing.
     */
    fun beginInstall(
        context: Context,
        apk: File,
        silent: Boolean,
        foreground: Boolean = false,
        stashPrevious: Boolean = true,
        // Rollback only: the target APK is an OLDER versionCode than what's
        // installed, so it must go through a session that requests a
        // downgrade (see [downgradeInstall]). The plain ACTION_VIEW / silent
        // paths reject an older versionCode — ACTION_VIEW with the opaque
        // "package appears to be invalid", the silent session with
        // INSTALL_FAILED_VERSION_DOWNGRADE.
        downgrade: Boolean = false,
    ) {
        // Off-main: silentInstall copies the whole APK (~80 MB) into
        // the PackageInstaller session before commit. On the main
        // thread that froze the UI for several seconds — user-visible
        // as "first tap does nothing, must tap twice" because the
        // taps queued during the freeze. stashCurrentApk likewise
        // does a copy of the running APK to filesDir; same justification.
        // ACTION_VIEW (startInstallActivity) is cheap so it could stay
        // on main, but routing everything through one off-main path
        // keeps the call site simple.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        app.aether.aegis.AegisApp.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Backstop parse check. The download path already verifies
            // size + checksum, but ANY route into beginInstall (rollback,
            // a stale on-disk update.apk, a future caller) must not feed
            // a malformed file to PackageInstaller — that surfaces to the
            // user as the opaque system error "package appears to be
            // invalid" with no recovery. getPackageArchiveInfo returns
            // null for anything the platform parser can't read; bail to a
            // clear Failed state instead (user-reported 2026-06-07).
            // Validate the WHOLE archive, not just the manifest.
            // getPackageArchiveInfo only parses AndroidManifest.xml, which
            // sits near the START of the zip — so a TRUNCATED apk (e.g. a
            // stash the process was killed mid-copy) passes it yet the system
            // installer still rejects it as "package appears to be invalid",
            // because the zip central directory at the END is missing. Opening
            // it as a ZipFile reads that central directory and throws on a
            // truncated/corrupt archive, catching exactly that case.
            val invalidReason: String? = try {
                when {
                    context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) == null ->
                        "manifest unreadable"
                    else -> java.util.zip.ZipFile(apk).use {
                        if (it.size() <= 0) "empty archive" else null
                    }
                }
            } catch (t: Throwable) {
                "corrupt/truncated archive: ${t.message}"
            }
            if (invalidReason != null) {
                Log.w(TAG, "refusing to install invalid APK (${apk.absolutePath}): $invalidReason")
                runCatching { apk.delete() }
                UpdateState.set(
                    UpdateState.Status.Failed(
                        "Update file was corrupt — tap to retry.",
                    ),
                )
                return@launch
            }
            if (stashPrevious) {
                runCatching { stashCurrentApk(context) }
                    .onFailure { Log.w(TAG, "could not stash current APK: $it") }
            }
            if (downgrade) {
                // Rollback: an older versionCode can only be installed
                // through a session that explicitly requests a downgrade.
                // Routing here (instead of ACTION_VIEW) ALSO means a
                // rejection comes back through InstallSessionReceiver as a
                // concrete reason ("Install failed: …") instead of the
                // system installer's opaque "package appears to be invalid".
                downgradeInstall(context, apk)
                return@launch
            }
            if (silent) {
                // Drop the prior `isDeviceOwner` gate. On Android 12+
                // a regular app with UPDATE_PACKAGES_WITHOUT_USER_ACTION
                // (manifest line 94, normal-protection so auto-granted)
                // can silent-self-update when (a) it's the installer of
                // record for the existing install and (b) signing
                // matches. The PackageInstaller session enforces all
                // of that — if any check fails it returns
                // STATUS_PENDING_USER_ACTION and InstallSessionReceiver
                // surfaces the system dialog as the fallback. That
                // means trying the silent path costs nothing on
                // non-DO devices and works automatically on Pixel
                // (which never gets DO but does honour the manifest
                // permission). User-reported 2026.05.615 "silent
                // update STILL not silent on pixel".
                silentInstall(context, apk)
            } else if (foreground) {
                startInstallActivity(context, apk)
            } else {
                promptInstall(context, apk)
            }
        }
    }

    /**
     * Returns true iff filesDir/update.apk exists AND its versionCode
     * is strictly newer than what's installed. Side-effect: deletes
     * stale APKs and cancels lingering install notifications so the
     * in-app banner self-extinguishes after a successful install.
     */
    fun hasPendingUpdate(context: Context): Boolean {
        val apk = File(context.filesDir, "update.apk")
        if (!apk.exists()) return false
        val pm = context.packageManager
        val apkInfo = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
            ?: run { apk.delete(); return false }
        val apkCode = apkInfo.longVersionCode
        val installedCode = pm.getPackageInfo(context.packageName, 0).longVersionCode
        return if (apkCode > installedCode) true
        else {
            apk.delete()
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
            false
        }
    }

    private fun startInstallActivity(context: Context, apk: File) {
        val installIntent = buildInstallIntent(context, apk)
        runCatching { context.startActivity(installIntent) }
            .onFailure {
                Log.w(TAG, "direct install activity launch failed; falling back to notification", it)
                promptInstall(context, apk)
            }
    }

    fun rollback(context: Context): Boolean {
        val previous = File(context.filesDir, "previous.apk")
        if (!previous.exists()) {
            Log.w(TAG, "no previous APK to roll back to")
            return false
        }
        // Blocklist the version we're rolling back FROM (the one currently
        // installed) so the auto-update paths don't immediately re-offer /
        // re-install it and undo the rollback the user just asked for (user
        // report 2026.06.14: "rolled-back build not blacklisted, still wants
        // to update"). This mirrors what BootHealthMonitor does for an
        // automatic crash-rollback. The block is honoured only by the
        // unattended paths (cold-start check + periodic worker); a manual
        // "Check for updates" still surfaces it for a user who deliberately
        // wants the newer build back.
        runCatching {
            val installed = context.packageManager
                .getPackageInfo(context.packageName, 0).longVersionCode
            UpdatePrefs(context).addKnownBadVersionCode(installed)
        }.onFailure { Log.w(TAG, "could not blocklist rolled-back-from version: $it") }
        // CRITICAL: stashPrevious=false. Default-true would overwrite
        // previous.apk with the CURRENT apk before the rollback fires
        // — defeating the rollback.
        //
        // downgrade=true routes through [downgradeInstall] — a
        // PackageInstaller session that requests a version downgrade.
        // The earlier approaches both failed on real devices:
        //   * silentInstall's session never set setRequestDowngrade(true),
        //     so a commit of an OLDER versionCode died with
        //     INSTALL_FAILED_VERSION_DOWNGRADE (user report 2026.06.200).
        //   * the ACTION_VIEW system installer was assumed to surface a
        //     downgrade-confirm dialog and proceed — it does NOT; it
        //     rejects the older APK with the opaque system toast
        //     "package appears to be invalid" and reports nothing back
        //     to the app (user report 2026.06.14). The file is a
        //     byte-complete, signature-valid archive (it passes the
        //     beginInstall parse/zip checks) — the rejection is the
        //     downgrade policy, not corruption.
        // The session path sets the downgrade flag and, because it goes
        // through InstallSessionReceiver, surfaces a concrete failure
        // reason if the platform still refuses (e.g. a non-debuggable
        // target, where userspace downgrade is disallowed regardless).
        beginInstall(
            context, previous,
            silent = false,
            foreground = false,
            stashPrevious = false,
            downgrade = true,
        )
        return true
    }

    private fun stashCurrentApk(context: Context) {
        val src = File(context.applicationInfo.sourceDir)
        val dst = File(context.filesDir, "previous.apk")
        val tmp = File(context.filesDir, "previous.apk.tmp")
        runCatching { tmp.delete() }
        try {
            // Write to a temp file and fsync BEFORE promoting it. A partial
            // stash (process killed mid-copy) used to land directly in
            // previous.apk and then fail the rollback as "package appears
            // invalid" — manifest intact, the rest of the zip missing.
            src.inputStream().use { input ->
                java.io.FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.flush()
                    out.fd.sync()
                }
            }
            // Only a byte-complete copy may become the rollback point.
            check(tmp.length() == src.length()) {
                "stash incomplete: ${tmp.length()}/${src.length()} bytes"
            }
            // Same-dir rename is atomic → previous.apk is always either the
            // old complete file or the new complete one, never a partial.
            if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun buildInstallIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun promptInstall(context: Context, apk: File) {
        val pi = PendingIntent.getActivity(
            context, 0, buildInstallIntent(context, apk),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notify(
            context,
            title = "Aegis update available",
            text = "Tap to install. Your data and settings will be preserved.",
            contentIntent = pi,
        )
    }

    private fun silentInstall(context: Context, apk: File) {
        try {
            val isDO = isDeviceOwner(context)
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                setAppPackageName(context.packageName)
                if (isDO) {
                    // Policy-driven install reason. INSTALL_REASON_POLICY
                    // is what enterprise MDM apps set to mark "this is a
                    // managed install, suppress all user confirmation."
                    // Combined with USER_ACTION_NOT_REQUIRED and DO
                    // status, Android treats the session as fully
                    // privileged and skips dialogs entirely. Without
                    // this, even DO + USER_ACTION_NOT_REQUIRED can demote
                    // to PENDING_USER_ACTION on some Pixel firmware
                    // because the OS doesn't know the install is being
                    // initiated by the device-management surface.
                    setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_POLICY)
                }
            }
            val sessionId = pi.createSession(params)
            pi.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("aegis", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val sender = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(InstallSessionReceiver.ACTION).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(sender)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "silent install failed, falling back to prompt", t)
            promptInstall(context, apk)
        }
    }

    /**
     * Rollback install — commits an OLDER versionCode through a
     * PackageInstaller session that requests a downgrade.
     *
     * `setRequestDowngrade(true)` is the platform switch that permits
     * replacing an installed app with a lower versionCode while keeping
     * its data. It is hidden API (`@SystemApi` on SessionParams), so we
     * reach it reflectively; Android honours it for a DEBUGGABLE target
     * (the debug channel) and for Device-Owner installs. If the method is
     * missing or the platform still refuses, we commit anyway so
     * [InstallSessionReceiver] reports the concrete failure reason
     * ("Install failed: INSTALL_FAILED_VERSION_DOWNGRADE", etc.) rather
     * than the system installer's opaque "package appears to be invalid".
     *
     * User action is intentionally NOT suppressed: a downgrade should be
     * a deliberate, confirmed act, and on a non-DO device the session
     * demotes to STATUS_PENDING_USER_ACTION anyway — the receiver then
     * surfaces the system confirm dialog.
     */
    private fun downgradeInstall(context: Context, apk: File) {
        try {
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                // Hidden API — reflective. Wrapped so an absent method on
                // some OEM build doesn't abort the attempt: without the
                // flag the commit just fails with a DOWNGRADE status the
                // receiver can report, which is still better than the
                // opaque ACTION_VIEW path.
                runCatching {
                    PackageInstaller.SessionParams::class.java
                        .getMethod("setRequestDowngrade", Boolean::class.javaPrimitiveType)
                        .invoke(this, true)
                }.onFailure { Log.w(TAG, "setRequestDowngrade unavailable: $it") }
                if (isDeviceOwner(context)) {
                    setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_POLICY)
                }
            }
            val sessionId = pi.createSession(params)
            pi.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("aegis-rollback", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val sender = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(InstallSessionReceiver.ACTION).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ).intentSender
                session.commit(sender)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "rollback install failed", t)
            UpdateState.set(UpdateState.Status.Failed("Rollback failed: ${t.message}"))
        }
    }

    /** True when Aegis is provisioned as the Device Owner — used by
     *  [silentInstall] to add INSTALL_REASON_POLICY and by
     *  [UpdateCheckWorker] to gate background auto-install. */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        return dpm?.isDeviceOwnerApp(context.packageName) == true
    }

    private fun notify(context: Context, title: String, text: String, contentIntent: PendingIntent? = null) {
        val notif = NotificationCompat.Builder(context, AegisApp.CHANNEL_SERVICE)
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
            .setColor(AegisApp.BRAND_CYAN_ARGB)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .apply { contentIntent?.let { setContentIntent(it).setAutoCancel(true) } }
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }
    }

    private const val TAG = "UpdateInstaller"
    private const val NOTIF_ID = 1500
}
