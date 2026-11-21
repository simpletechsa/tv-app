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

    /** Other 24-hour channel lives on the same YouTube channel (page link -> video id + title). */
    val youTubeFeeds = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, String>>>()

    /** Live video titles found during lookups, by video id. */
    val youTubeTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val ytTitle = Regex("""<meta name="title" content="([^"]*)">""")
    private val ytVideo = Regex(""""videoRenderer":\{"videoId":"([\w-]{11})"""")
    private val ytRunTitle = Regex(""""title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"""")
    private val ytLiveBadge = Regex("""BADGE_STYLE_TYPE_LIVE_NOW|"style":"LIVE"""")

    private fun page(u: String): String = open(u, Stream(u, userAgent = DESKTOP_UA), 15000, 30000) { _, _, input, _ ->
        input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun unescape(t: String) = t.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\\"", "\"").replace("\\/", "/").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    private val ytStart = Regex(""""startTimestamp":"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)([+-]\d\d):?(\d\d)"""")

    /** When a live started (from its page), or null if unknown. */
    private fun liveStart(html: String): Long? {
        val m = ytStart.find(html) ?: return null
        return try {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val local = f.parse(m.groupValues[1])!!.time
            val sign = if (m.groupValues[2].startsWith("-")) -1 else 1
            val off = (m.groupValues[2].drop(1).toInt() * 60 + m.groupValues[3].toInt()) * 60_000L * sign
            local - off
        } catch (e: Exception) { null }
    }

    /** A real 24-hour channel feed has been running for hours; talk shows / rallies / replays have not. */
    private const val FEED_MIN_HOURS = 4

    /**
     * Official YouTube live: returns the channel's own 24-hour live, or null if it isn't live now.
     * A news channel runs several lives (talk shows, rallies, events): the one that has been running
     * the longest is the real channel feed. Shorter lives are never used in its place.
     */
    fun youTubeLive(url: String): String? = try {
        val pageUrl = url.removePrefix("yt:")
        val html = page(pageUrl)
        // the channel's own profile picture = its logo
        ytAvatar.find(html)?.value?.let { a ->
            youTubeLogos[url] = a.replace(Regex("""=s\d+[^/]*$"""), "=s176-c-k-c0x00ffffff-no-rj")
        }
        val starts = HashMap<String, Long>()
        val mainId = if (ytLiveNow.containsMatchIn(html)) ytCanonical.find(html)?.groupValues?.get(1) else null
        if (mainId != null) {
            ytTitle.find(html)?.groupValues?.get(1)?.let { youTubeTitles[mainId] = unescape(it) }
            liveStart(html)?.let { starts[mainId] = it }
        }
        if (pageUrl.endsWith("/live")) {
            // every live on the channel right now, with its start time
            val others = LinkedHashSet<String>()
            try {
                val streams = page(pageUrl.removeSuffix("/live") + "/streams")
                for (part in streams.split("\"videoRenderer\":{").drop(1)) {
                    val chunk = "\"videoRenderer\":{" + part.take(6000)
                    val vid = ytVideo.find(chunk)?.groupValues?.get(1) ?: continue
                    if (!ytLiveBadge.containsMatchIn(chunk)) continue
                    youTubeTitles[vid] = unescape(ytRunTitle.find(chunk)?.groupValues?.get(1) ?: "")
                    if (vid != mainId) others += vid
                }
            } catch (_: Exception) { }
            for (vid in others.take(6)) {
                try {
                    val w = page("https://www.youtube.com/watch?v=$vid")
                    if (ytLiveNow.containsMatchIn(w)) liveStart(w)?.let { starts[vid] = it }
                } catch (_: Exception) { }
            }
        }
        // the longest-running live, and only if it has run long enough to be the channel's feed
        val now = System.currentTimeMillis()
        val chosen = if (starts.isEmpty()) mainId          // YouTube gave no start times at all: keep the channel's main live
            else starts.filterValues { now - it >= FEED_MIN_HOURS * 3_600_000L }.minByOrNull { it.value }?.key
        // other lives that are channels (running 12+ hours), not shows
        youTubeFeeds[url] = starts.filter { it.key != chosen && now - it.value >= 12 * 3_600_000L }
            .entries.sortedBy { it.value }.map { it.key to (youTubeTitles[it.key] ?: "") }
        chosen
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
