package app.aether.aegis.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tap-to-preview link metadata.
 *
 * GUI_SPEC asks for "Link Previews" but auto-fetching the URL on the
 * recipient's side leaks two things to the link's host: the recipient's
 * IP, and the fact that they opened the message. Aegis is privacy-first,
 * so previews are opt-in: the chat bubble shows a "Show preview" affordance
 * the user can tap, which then fetches metadata. The fetch is bounded
 * (8s timeout, first 64 KB only) and parsed for <title> + og:description.
 *
 * Cache lives in memory only — a process restart re-fetches, which is
 * fine. No on-disk leak of which links the user previewed.
 */
object LinkPreview {

    data class Snapshot(
        val title: String?,
        val description: String?,
    )

    sealed class Result {
        data class Ok(val snap: Snapshot) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val cache = ConcurrentHashMap<String, Snapshot>()
    private val http = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun cached(url: String): Snapshot? = cache[url]

    suspend fun fetch(url: String): Result = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext Result.Ok(it) }
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    // Identify ourselves so a server admin reading logs
                    // sees the source. No version leak.
                    "AegisLinkPreview/1.0 (+aegis)",
                )
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.Failed("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext Result.Failed("empty body")
                // Cap at 64 KB — most metadata lives in the <head>, no
                // need to slurp megabytes from heavy pages.
                val source = body.source()
                source.request(64L * 1024L)
                val head = source.buffer.snapshot().utf8()
                val snap = parseMetadata(head)
                if (snap.title == null && snap.description == null) {
                    return@withContext Result.Failed("no metadata found")
                }
                cache[url] = snap
                Result.Ok(snap)
            }
        }.getOrElse { Result.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    /** Parse a chunk of HTML for the title and best description.
     *  og:description preferred, then twitter:description, then
     *  <meta name="description">. Robust to attribute ordering
     *  (property may come before or after content). */
    internal fun parseMetadata(html: String): Snapshot {
        val title = TITLE_RE.find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
            ?.takeIf { it.isNotBlank() }
        val desc = pickDescription(html)
        return Snapshot(title = title, description = desc)
    }

    private fun pickDescription(html: String): String? {
        // Try each meta variant. Stop at the first non-blank hit.
        for (re in DESC_RES) {
            val m = re.find(html) ?: continue
            val raw = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: continue
            return decodeEntities(raw).trim().take(300)
        }
        return null
    }

    /** Decode the handful of HTML entities that show up in OG fields. */
    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private val TITLE_RE = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    // Each regex captures the value in group 1. Both attribute orderings
    // (property/content and content/property) covered.
    private val DESC_RES = listOf(
        Regex(
            "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]+property=[\"']og:description[\"']",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "<meta[^>]+name=[\"']twitter:description[\"'][^>]+content=[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE,
        ),
    )
}
