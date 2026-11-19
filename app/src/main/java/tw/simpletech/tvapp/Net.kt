package tw.simpletech.tvapp

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** Download a text file (playlist). Follows redirects. */
    fun getText(url: String): String {
        var u = url
        repeat(5) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    u = URL(URL(u), c.getHeaderField("Location") ?: error("bad redirect")).toString()
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
        error("too many redirects")
    }

    /** Test one stream link: "ok", "geo" (blocked here) or "dead". */
    fun test(s: Stream): String {
        var u = s.url
        try {
            repeat(5) {
                val c = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000; readTimeout = 8000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", s.userAgent ?: UA)
                    s.referrer?.let { setRequestProperty("Referer", it) }
                }
                try {
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: return "dead"
                        u = URL(URL(u), loc).toString()
                        return@repeat
                    }
                    if (code == 401 || code == 403 || code == 451) return "geo"
                    if (code !in 200..299) return "dead"
                    val ct = (c.contentType ?: "").lowercase()
                    val buf = ByteArray(4096)
                    val n = c.inputStream.read(buf).coerceAtLeast(0)
                    val head = String(buf, 0, n, Charsets.ISO_8859_1)
                    val ok = head.contains("#EXTM3U") || ct.contains("mpegurl") ||
                        ct.startsWith("video/") || ct.contains("octet-stream") ||
                        ct.contains("dash") || head.contains("<MPD") ||
                        (n > 0 && buf[0] == 0x47.toByte())
                    return if (ok) "ok" else "dead"
                } finally {
                    c.disconnect()
                }
            }
            return "dead"
        } catch (e: SocketTimeoutException) {
            return if (s.geo) "geo" else "dead"
        } catch (e: Exception) {
            return "dead"
        }
    }
}
