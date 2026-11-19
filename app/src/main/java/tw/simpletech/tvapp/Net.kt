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

    /** Official YouTube live: returns the current live video id, or null if not live now. */
    fun youTubeLive(url: String): String? = try {
        val html = open(url.removePrefix("yt:"), Stream(url, userAgent = DESKTOP_UA), 15000, 30000) { _, _, input, _ ->
            input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        }
        if (ytLiveNow.containsMatchIn(html)) ytCanonical.find(html)?.groupValues?.get(1) else null
    } catch (e: Exception) {
        null
    }

    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    fun check(s: Stream): String {
        if (s.isYouTube) return if (youTubeLive(s.url) != null) "ok" else "dead"
        try {
            var r = get(s.url, s, 65536)
            if (blocked(r.code)) return "geo"
            if (r.code !in 200..299) return "dead"
            var text = r.text
            if (text.contains("#EXTM3U")) {
                if (text.contains("#EXT-X-STREAM-INF")) {
                    val v = firstUri(text) ?: return "dead"
                    r = get(resolve(r.url, v), s, 65536)
                    if (blocked(r.code)) return "geo"
                    if (r.code !in 200..299) return "dead"
                    text = r.text
                    if (!text.contains("#EXTM3U")) return "dead"
                }
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
