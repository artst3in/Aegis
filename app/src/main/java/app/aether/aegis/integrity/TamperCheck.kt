package app.aether.aegis.integrity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest

/**
 * Best-effort device-integrity signals (tamper signals, warn-only).
 *
 * ## Warn-only, by deliberate policy
 *
 * These checks NEVER block, wipe, or degrade the app. Aegis's target
 * users — people in unsafe situations, journalists, activists — quite
 * often run rooted or custom-ROM phones ON PURPOSE (GrapheneOS, Magisk
 * for firewalling, etc.). A hard root-block would brick exactly the
 * users the app exists for. So every signal here is advisory: surface a
 * one-time warning and a Diagnostics flag, and let the adult owner
 * decide. Never a hard block — a hard block would brick legitimate
 * rooted users.
 *
 * ## What it can and cannot tell you
 *
 * None of this is a security boundary — a determined attacker on a
 * rooted device defeats every userspace check. The value is catching
 * the *accidental* or *opportunistic* case: a debugger left attached, an
 * obviously-tampered build, a casually-rooted handset the owner forgot
 * about. Treat the output as a smoke detector, not a vault door.
 */
object TamperCheck {

    /** Common superuser / root-manager artefacts. Presence of any is a
     *  strong hint the device is rooted. Not exhaustive and trivially
     *  hidden by a serious adversary — see class doc. */
    private val ROOT_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
        "/system/bin/magisk",
        "/data/adb/magisk",
        "/data/adb/modules",
    )

    /** True iff any well-known root artefact is present OR the build is
     *  signed with the public Android test-keys (a custom/engineering
     *  build). Wrapped so a SecurityException on a locked-down path can
     *  never crash the caller. */
    fun isLikelyRooted(): Boolean = runCatching {
        val tags = Build.TAGS
        val testKeys = tags != null && tags.contains("test-keys")
        testKeys || ROOT_PATHS.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }.getOrDefault(false)

    /** True iff a debugger is currently attached (or one is waiting to
     *  attach). Distinct from the build's debuggable FLAG — the debug
     *  channel is debuggable BY DESIGN, so we report the live attach
     *  state, not the manifest flag, to avoid false positives on Aegis's
     *  own debug build. */
    fun isDebuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    /**
     * SHA-256 (upper-hex, colon-free) of the APK's current signing
     * certificate, or null if it can't be read. Exposed for the
     * Diagnostics screen so the owner can eyeball it against the known
     * Aegis release fingerprint — a mismatch means a
     * repackaged build. We intentionally do NOT hardcode an expected
     * value and self-block on mismatch: that would be a hard block, and
     * the cert differs across the debug/release keystores anyway.
     */
    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    fun signerSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return null
        val first = signatures.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        digest.joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    /**
     * Human-readable advisory signals for the current device, suitable
     * for a one-time warning and the Diagnostics screen. Empty list =
     * nothing notable detected. Never throws.
     */
    fun signals(context: Context): List<String> = buildList {
        if (isLikelyRooted()) {
            add("This device looks rooted. That's fine if it's yours on purpose, " +
                "but root weakens the hardware protections Aegis relies on.")
        }
        if (isDebuggerAttached()) {
            add("A debugger is attached to Aegis. If you didn't do this deliberately, " +
                "close it — a debugger can read app memory.")
        }
    }
}
