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

    /**
     * Tests a link the way a player would: playlist -> quality level -> first video piece.
     * Returns true only if real video data comes back.
     */
    fun works(s: Stream): Boolean {
        try {
            var r = get(s.url, s, 65536)
            if (r.code !in 200..299) return false
            var text = r.text
            if (text.contains("#EXTM3U")) {
                if (text.contains("#EXT-X-STREAM-INF")) {
                    val v = firstUri(text) ?: return false
                    r = get(resolve(r.url, v), s, 65536)
                    if (r.code !in 200..299) return false
                    text = r.text
                    if (!text.contains("#EXTM3U")) return false
                }
                val seg = firstUri(text) ?: return false
                val sr = get(resolve(r.url, seg), s, 4096)
                return sr.code in 200..299 && sr.body.isNotEmpty()
            }
            val t = r.type
            return t.startsWith("video/") || t.contains("octet-stream") || t.contains("dash") ||
                text.contains("<MPD") || (r.body.isNotEmpty() && r.body[0] == 0x47.toByte())
        } catch (e: SocketTimeoutException) {
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
