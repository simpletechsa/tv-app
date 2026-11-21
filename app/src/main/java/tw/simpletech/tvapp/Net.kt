package tw.simpletech.tvapp

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    class Resp(val code: Int, val type: String, val body: ByteArray, val url: String) {
        val text get() = String(body, Charsets.ISO_8859_1)
    }

    /** Opens a URL (following redirects) and hands the open stream to [use]. */
    fun <T> open(url: String, s: Stream?, connectMs: Int, readMs: Int, use: (Int, String, InputStream?, String) -> T): T {
        var u = url
        repeat(6) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectMs; readTimeout = readMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", s?.userAgent ?: UA)
                s?.referrer?.let { setRequestProperty("Referer", it) }
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return use(code, "", null, u)
                    u = URL(URL(u), loc).toString()
                    return@repeat
                }
                val stream = if (code in 200..299) c.inputStream else null
                return use(code, (c.contentType ?: "").lowercase(), stream, u)
            } finally {
                c.disconnect()
            }
        }
        error("too many redirects")
    }

    fun getText(url: String): String = open(url, null, 15000, 30000) { code, _, input, _ ->
        if (input == null) error("HTTP $code")
        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun get(url: String, s: Stream, max: Int): Resp = open(url, s, 6000, 8000) { code, type, input, finalUrl ->
        val buf = ByteArray(max)
        var n = 0
        if (input != null) {
            while (n < max) {
                val r = input.read(buf, n, max - n)
                if (r <= 0) break
                n += r
                if (max <= 4096) break          // segment test: first bytes are enough
            }
        }
        Resp(code, type, buf.copyOf(n), finalUrl)
    }

    private fun firstUri(playlist: String): String? =
        playlist.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun resolve(base: String, rel: String) = URL(URL(base), rel).toString()

    private fun blocked(code: Int) = code == 401 || code == 403 || code == 451

    /**
     * Tests a link the way a player would: playlist -> quality level -> first video piece.
     * Returns "ok" (real video came back), "geo" (blocked in this country) or "dead".
     */
    private val ytCanonical = Regex("""<link rel="canonical" href="https://www\.youtube\.com/watch\?v=([\w-]{11})""")
    private val ytLiveNow = Regex(""""isLiveNow"\s*:\s*true""")

    private val ytAvatar = Regex("""https://yt3\.(?:ggpht|googleusercontent)\.com/[A-Za-z0-9_\-./=]+""")

    /** Channel picture (logo) found on the last YouTube page that was checked, by page URL. */
    val youTubeLogos = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Live video titles found during lookups, by video id. */
    val youTubeTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val ytTitle = Regex("""<meta name="title" content="([^"]*)">""")
    private val ytVideo = Regex(""""videoRenderer":\{"videoId":"([\w-]{11})"""")
    private val ytRunTitle = Regex(""""title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"""")
    private val ytLiveBadge = Regex("""BADGE_STYLE_TYPE_LIVE_NOW|"style":"LIVE"""")
    private val is24h = Regex("""24\s*(小時|h|hr|hour|H)|24/7|二十四小時|全天""", RegexOption.IGNORE_CASE)

    private fun page(u: String): String = open(u, Stream(u, userAgent = DESKTOP_UA), 15000, 30000) { _, _, input, _ ->
        input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun unescape(t: String) = t.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\\"", "\"").replace("\\/", "/").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    /**
     * Official YouTube live: returns the current live video id, or null if not live now.
     * News channels run several lives (talk shows, rallies); the 24-hour news live is preferred.
     */
    fun youTubeLive(url: String): String? = try {
        val pageUrl = url.removePrefix("yt:")
        val html = page(pageUrl)
        // the channel's own profile picture = its logo
        ytAvatar.find(html)?.value?.let { a ->
            youTubeLogos[url] = a.replace(Regex("""=s\d+[^/]*$"""), "=s176-c-k-c0x00ffffff-no-rj")
        }
        var id = if (ytLiveNow.containsMatchIn(html)) ytCanonical.find(html)?.groupValues?.get(1) else null
        if (id != null) ytTitle.find(html)?.groupValues?.get(1)?.let { youTubeTitles[id!!] = unescape(it) }
        // Look at all lives on the channel; take the 24-hour one if there is one
        if (pageUrl.endsWith("/live") && (id == null || youTubeTitles[id]?.let { is24h.containsMatchIn(it) } != true)) {
            try {
                val streams = page(pageUrl.removeSuffix("/live") + "/streams")
                val parts = streams.split("\"videoRenderer\":{").drop(1)
                for (part in parts) {
                    val chunk = "\"videoRenderer\":{" + part.take(6000)
                    val vid = ytVideo.find(chunk)?.groupValues?.get(1) ?: continue
                    if (!ytLiveBadge.containsMatchIn(chunk)) continue
                    val t = unescape(ytRunTitle.find(chunk)?.groupValues?.get(1) ?: "")
                    youTubeTitles[vid] = t
                    if (is24h.containsMatchIn(t)) { id = vid; break }
                }
            } catch (_: Exception) { /* keep the /live result */ }
        }
        id
    } catch (e: Exception) {
        null
    }

    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** [relaxed] = accept a stream whose playlist answers, even if the first video piece test fails. */
    fun check(s: Stream, relaxed: Boolean = false): String {
        if (s.isYouTube) return "dead"
        try {
            var r = get(s.url, s, 65536)
            if (blocked(r.code)) return "geo"
            if (r.code !in 200..299) return "dead"
            var text = r.text
            if (text.contains("#EXTM3U")) {
                if (text.contains("#EXT-X-STREAM-INF")) {
                    val v = firstUri(text) ?: return "dead"
                    r = get(resolve(r.url, v), s, 65536)
                    if (blocked(r.code)) return if (relaxed) "ok" else "geo"
                    if (r.code !in 200..299) return if (relaxed) "ok" else "dead"
                    text = r.text
                    if (!text.contains("#EXTM3U")) return if (relaxed) "ok" else "dead"
                }
                if (relaxed) return "ok"
                val seg = firstUri(text) ?: return "dead"
                val sr = get(resolve(r.url, seg), s, 4096)
                if (blocked(sr.code)) return "geo"
                return if (sr.code in 200..299 && sr.body.isNotEmpty()) "ok" else "dead"
            }
            val t = r.type
            val ok = t.startsWith("video/") || t.contains("octet-stream") || t.contains("dash") ||
                text.contains("<MPD") || (r.body.isNotEmpty() && r.body[0] == 0x47.toByte())
            return if (ok) "ok" else "dead"
        } catch (e: Exception) {
            return if (s.geo) "geo" else "dead"
        }
    }
}
