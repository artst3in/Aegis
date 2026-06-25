package app.aether.aegis.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Auto-update channel via GitHub Releases.
 *
 * Each build is published as a Release on the channel's repo; the APK is
 * a release ASSET (stored outside git, so the repo's history never
 * accumulates ~84 MB binaries). The client lists releases and picks the
 * newest one carrying THIS channel's APK asset (not /releases/latest, so
 * both channels can safely share a repo — e.g. publishing both to
 * the private dev repo for testing), reads the embedded manifest from that
 * release body, and compares the remote versionCode against the running app's.
 *
 *   debug   → the private dev repo (read PAT lists + downloads)
 *   release → artst3in/Aegis       (public; anonymous)
 *
 * Manifest transport: the publish step embeds a one-line marker in the
 * release body —
 *   <!--aegis-manifest:{"versionCode":…,"versionName":"…",
 *       "buildDna":"…","gitSha":"…","sha256":"…","size":…}-->
 * — so a single API call answers "is there an update?" without
 * downloading any asset (the HTML comment renders invisibly on the
 * Releases page, keeping human notes clean). Integrity is the APK's
 * SHA-256 from that manifest (release assets carry no git-blob SHA), plus
 * the advertised byte size; both are verified after download.
 *
 * Asset download: the asset API 302-redirects to a signed CDN URL. We
 * resolve that redirect WITHOUT following it with the GitHub token
 * attached (the CDN rejects a request bearing both a signed query and an
 * Authorization header), then stream the CDN URL tokenless.
 */
class UpdateClient(
    private val http: OkHttpClient = defaultHttpClient(),
    /** GitHub PAT for the private repo. Optional — if null, anonymous; for a
     *  private repo, anonymous gets 404 and check() reports it cleanly. */
    private val token: String? = null,
) {
    // Same connection settings as [http] but it does NOT auto-follow
    // redirects — used to capture the asset API's 302 Location so the
    // GitHub token is never forwarded to the signed CDN URL.
    private val noRedirectHttp: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    data class ReleaseInfo(
        /** SHA-256 of the remote APK (hex). Doubles as the content
         *  comparator used to skip re-downloading an APK already on disk,
         *  and as the dedup/dismiss key in the "update available" UI. */
        val sha: String,
        val shortSha: String,
        val downloadUrl: String,
        val notes: String,
        /** Authoritative Android versionCode from the release manifest.
         *  UpdateCheckWorker uses it to skip auto-install of a versionCode
         *  that previously triggered a rollback (BootHealthMonitor marks
         *  them in UpdatePrefs), so a broken build can't spin the user in
         *  an install → crash → rollback → re-install loop. */
        val versionCode: Long,
        /** Advertised asset size in bytes (0 = unknown). Verified after
         *  download so a truncated transfer can't reach the installer. */
        val size: Long = 0,
    )

    /** Result of a check, with reason information surfaced to the UI. */
    sealed class CheckOutcome {
        data class UpdateAvailable(val release: ReleaseInfo) : CheckOutcome()
        data object UpToDate : CheckOutcome()
        data object NeedsToken : CheckOutcome()         // 404 from private repo
        data class RateLimited(val resetAt: Long?) : CheckOutcome() // 403 with X-RateLimit-Remaining: 0
        data class Failed(val reason: String) : CheckOutcome()
    }

    /**
     * `context` is required to read the running app's versionCode from
     * PackageManager — authoritative, no APK file inspection.
     */
    suspend fun check(context: Context): CheckOutcome = withContext(Dispatchers.IO) {
        val isReleaseChannel = app.aether.aegis.BuildConfig.RELEASE_CHANNEL == "release"
        // Channel-aware APK asset name. The release build (applicationId
        // app.aether.aegis) MUST pull the release asset; the debug build
        // pulls the per-ABI debug asset.
        val abis = android.os.Build.SUPPORTED_ABIS
        val apkAssetName = when {
            isReleaseChannel                 -> "aegis-release.apk"
            abis.any { it == "arm64-v8a" }   -> "aegis-debug.apk"
            abis.any { it == "armeabi-v7a" } -> "aegis-debug-armv7.apk"
            else                              -> "aegis-debug.apk"
        }
        // List releases (newest first) and pick the most recent one that
        // actually carries THIS channel's APK asset — NOT /releases/latest,
        // which returns the single newest release repo-wide. That matters
        // because the two channels can share a repo (e.g. publishing both
        // to the private dev repo for testing): a debug app must skip a newer
        // release-only build instead of failing on a missing asset.
        val releaseReq = githubReq(
            "https://api.github.com/repos/$REPO/releases?per_page=20",
        ).build()
        runCatching {
            http.newCall(releaseReq).execute().use { resp ->
                when {
                    resp.code == 404 -> {
                        // Listing 404s when a PRIVATE repo rejects an
                        // anonymous/invalid token. (A repo with no releases
                        // returns 200 []). Disambiguate by channel: the
                        // private debug channel with no token means "auth
                        // needed"; otherwise treat as up to date.
                        return@withContext if (!isReleaseChannel && token.isNullOrBlank())
                            CheckOutcome.NeedsToken else CheckOutcome.UpToDate
                    }
                    resp.code == 403 -> {
                        val remaining = resp.header("X-RateLimit-Remaining")
                        return@withContext if (remaining == "0") {
                            val reset = resp.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                            CheckOutcome.RateLimited(reset)
                        } else CheckOutcome.Failed("HTTP 403 (token missing required scope?)")
                    }
                    !resp.isSuccessful -> return@withContext CheckOutcome.Failed("HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: return@withContext CheckOutcome.Failed("empty body")
                val releases = runCatching { org.json.JSONArray(body) }.getOrNull()
                    ?: return@withContext CheckOutcome.Failed("releases parse failed")

                // Walk newest → oldest; the first non-draft release that
                // carries this channel's asset AND a valid manifest wins.
                var manifest: JSONObject? = null
                var assetApiUrl = ""
                var assetSize = 0L
                for (i in 0 until releases.length()) {
                    val rel = releases.optJSONObject(i) ?: continue
                    if (rel.optBoolean("draft", false)) continue
                    val assets = rel.optJSONArray("assets") ?: continue
                    var url = ""
                    var size = 0L
                    for (j in 0 until assets.length()) {
                        val a = assets.optJSONObject(j) ?: continue
                        if (a.optString("name") == apkAssetName) {
                            url = a.optString("url")
                            size = a.optLong("size", 0L)
                            break
                        }
                    }
                    if (url.isBlank()) continue
                    val m = manifestFromBody(rel.optString("body")) ?: continue
                    if (m.optLong("versionCode", -1L) <= 0) continue
                    manifest = m
                    assetApiUrl = url
                    assetSize = size
                    break
                }
                // No release for this channel yet → nothing to update to.
                if (manifest == null) return@withContext CheckOutcome.UpToDate

                val remoteVersionCode = manifest.optLong("versionCode", -1L)
                val pm = context.packageManager
                val localVersionCode = runCatching {
                    pm.getPackageInfo(context.packageName, 0).longVersionCode
                }.getOrDefault(0L)
                if (remoteVersionCode <= localVersionCode) return@withContext CheckOutcome.UpToDate
                // Blocklisted build — one the user rolled back from, or one
                // BootHealthMonitor crash-rolled. Never offer it again on ANY
                // path (manual "Check for updates" included), only until a
                // strictly NEWER, non-blocklisted build supersedes it: this
                // is the single chokepoint every detection path funnels
                // through, so suppressing it here stops the updater from
                // re-offering the exact version the user just rejected (user
                // report 2026.06.14). A higher build clears the deadlock
                // naturally — its versionCode isn't in the set, so it's
                // offered as normal; the rolled-back-from code just stays
                // skipped forever.
                if (UpdatePrefs(context).knownBadVersionCodes.contains(remoteVersionCode)) {
                    return@withContext CheckOutcome.UpToDate
                }

                // Per-asset SHA-256 — the debug channel ships multiple
                // ABIs (different hashes) under one release, so the
                // integrity hash is keyed by asset name. Falls back to a
                // top-level "sha256" for a single-asset release.
                val sha256 = manifest.optJSONObject("assets")
                    ?.optJSONObject(apkAssetName)?.optString("sha256")
                    ?.ifBlank { null }
                    ?: manifest.optString("sha256")
                val remoteVersionName = manifest.optString("versionName")
                val remoteBuildDna = manifest.optString("buildDna").ifBlank { "?" }
                val remoteGitSha = manifest.optString("gitSha")
                CheckOutcome.UpdateAvailable(
                    ReleaseInfo(
                        sha = sha256,
                        shortSha = remoteVersionName.ifBlank { sha256.take(7) },
                        downloadUrl = assetApiUrl,
                        notes = "$remoteVersionName · $remoteBuildDna · ${remoteGitSha.take(7)}",
                        versionCode = remoteVersionCode,
                        size = if (assetSize > 0) assetSize else manifest.optLong("size", 0L),
                    ),
                )
            }
        }.getOrElse { CheckOutcome.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    /**
     * Download the release APK, optionally reporting progress as bytes
     * stream in. [onProgress] fires throttled to ~10 Hz max so the
     * StateFlow downstream doesn't recompose Compose on every chunk.
     * `total` is null when the server didn't advertise Content-Length.
     *
     * [isCancelled] is checked every chunk: on cancel the loop bails, the
     * okhttp call is aborted, the .part file is removed, and the method
     * returns [DownloadOutcome.Cancelled].
     */
    sealed class DownloadOutcome {
        data object Ok : DownloadOutcome()
        data object Cancelled : DownloadOutcome()
        data class Failed(val reason: String) : DownloadOutcome()
    }

    suspend fun downloadApk(
        release: ReleaseInfo,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: ((bytes: Long, total: Long?) -> Unit)? = null,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        // Resolve the asset API URL to its signed CDN target WITHOUT
        // following the redirect with the GitHub token attached — the CDN
        // rejects a request carrying both a signed query and an
        // Authorization header. We capture the 302 Location, then stream
        // it tokenless.
        val byteSourceReq: Request = runCatching {
            val assetReq = githubReq(release.downloadUrl)
                .header("Accept", "application/octet-stream")
                .build()
            noRedirectHttp.newCall(assetReq).execute().use { r ->
                when {
                    r.isRedirect -> {
                        val loc = r.header("Location")
                            ?: throw IOException("asset redirect without Location")
                        Request.Builder().url(loc).build()  // CDN URL, tokenless
                    }
                    // Rare: the API streamed bytes directly (no redirect).
                    // Re-issue the authenticated request through the
                    // following client so we can read the body fresh.
                    r.isSuccessful -> assetReq
                    else -> throw IOException("HTTP ${r.code} resolving asset")
                }
            }
        }.getOrElse {
            Log.w(TAG, "asset resolve failed: $it")
            return@withContext DownloadOutcome.Failed(it.message ?: "asset resolve error")
        }

        val partial = File(dest.parentFile, dest.name + ".part")
        var cancelled = false
        // Captured from the response so the verifier can assert we received
        // every byte. A clean EOF on a short stream (server closed early)
        // otherwise yields a truncated APK that installs as "package
        // appears to be invalid".
        var announcedTotal: Long? = null
        val call = http.newCall(byteSourceReq)
        val result = runCatching {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} for ${release.downloadUrl}")
                }
                val respBody = resp.body ?: throw IOException("empty body")
                val total = respBody.contentLength().takeIf { it > 0L }
                announcedTotal = total
                partial.outputStream().use { out ->
                    val input = respBody.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var read: Long = 0
                    var lastReport = 0L
                    onProgress?.invoke(0L, total)
                    while (true) {
                        if (isCancelled()) {
                            cancelled = true
                            call.cancel()
                            break
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (onProgress != null && now - lastReport >= 100L) {
                            onProgress(read, total)
                            lastReport = now
                        }
                    }
                    onProgress?.invoke(read, total)
                }
            }
        }
        when {
            cancelled -> {
                partial.delete()
                DownloadOutcome.Cancelled
            }
            result.isFailure -> {
                Log.w(TAG, "download failed: ${result.exceptionOrNull()}")
                partial.delete()
                DownloadOutcome.Failed(
                    result.exceptionOrNull()?.message ?: "download error",
                )
            }
            else -> {
                // Integrity gate — NEVER hand an unverified binary to
                // PackageInstaller. Cheapest checks first:
                //   1. Floor size: a sub-1MB "APK" is an error page.
                //   2. Length match: every advertised byte must have
                //      arrived (catches a clean-EOF truncation okhttp
                //      doesn't raise on).
                //   3. SHA-256: the bytes on disk must hash to the
                //      manifest's APK SHA-256 (catches corruption that
                //      preserves length).
                val size = partial.length()
                val expectedTotal = announcedTotal ?: release.size.takeIf { it > 0 }
                val sha = runCatching { sha256Of(partial) }.getOrNull()
                when {
                    size < 1_000_000 -> {
                        partial.delete()
                        DownloadOutcome.Failed("download too small: $size bytes")
                    }
                    expectedTotal != null && size != expectedTotal -> {
                        partial.delete()
                        DownloadOutcome.Failed(
                            "download incomplete: $size of $expectedTotal bytes",
                        )
                    }
                    release.sha.isNotBlank() && sha != release.sha -> {
                        partial.delete()
                        DownloadOutcome.Failed("download corrupt (checksum mismatch)")
                    }
                    else -> {
                        if (dest.exists()) dest.delete()
                        if (!partial.renameTo(dest)) {
                            partial.delete()
                            DownloadOutcome.Failed("atomic rename failed")
                        } else DownloadOutcome.Ok
                    }
                }
            }
        }
    }

    private fun githubReq(url: String): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (!token.isNullOrBlank()) b.header("Authorization", "token $token")
        return b
    }

    /**
     * Pull the machine manifest out of a release body's
     * `<!--aegis-manifest:{…}-->` marker. Returns null when the marker or
     * its JSON is missing/malformed (caller treats that as a failed
     * check rather than guessing a version).
     */
    private fun manifestFromBody(body: String): JSONObject? {
        val marker = "aegis-manifest:"
        val i = body.indexOf(marker)
        if (i < 0) return null
        val rest = body.substring(i + marker.length)
        val end = rest.indexOf("-->")
        val jsonStr = (if (end >= 0) rest.substring(0, end) else rest).trim()
        return runCatching { JSONObject(jsonStr) }.getOrNull()
    }

    companion object {
        private const val TAG = "UpdateClient"

        /**
         * HTTP client tuned for update traffic. The default 10 s read
         * timeout is far too tight for streaming a tens-of-MB APK over a
         * slow/flaky mobile or Tor link. Generous per-op timeouts + no
         * overall call cap let a large transfer ride out brief stalls
         * (paired with the size + SHA-256 verification, a genuinely bad
         * transfer is still rejected).
         */
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        // Source repo for update polls — flipped at build time by the
        // Gradle release-channel split. BuildConfig.UPDATE_REPO is the
        // single point of truth so the rest of the file is variant-agnostic.
        private val REPO = app.aether.aegis.BuildConfig.UPDATE_REPO

        /**
         * SHA-256 (hex) of a file, streamed. Used both to verify a
         * finished download against the release manifest and to let
         * callers skip re-downloading an APK already on disk whose content
         * matches the remote.
         */
        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
