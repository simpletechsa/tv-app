package tw.simpletech.tvapp

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * TV guide (program schedule) from free XMLTV files (epgshare01).
 * Channels are matched by name, so not every channel will have a guide.
 */
object Epg {
    private val SOURCES = listOf("TW1", "ZA1", "UK1", "US2", "AU1", "NZ1", "KR1", "JP1")
        .map { "https://epgshare01.online/epgshare01/epg_ripper_$it.xml.gz" }

    private val drop = Regex("""\b(hd|sd|fhd|uhd|4k|tv|channel|east|west)\b""")

    /** Simplify a name for matching: "BBC News HD" -> "bbcnews". */
    fun norm(s: String): String {
        val low = s.lowercase().substringBefore('@')
            .replace(Regex("""\.[a-z]{2}$"""), "")          // remove ".uk" style country end
            .replace('.', ' ').replace('_', ' ')
        return drop.replace(low, " ").filter { it.isLetterOrDigit() }
    }

    private fun aliases(ch: Channel): Set<String> {
        val out = HashSet<String>()
        out += norm(ch.name)
        out += norm(ch.key.removePrefix("my:"))
        // "台視新聞 TTV News" -> also "台視新聞" and "TTV News"
        val parts = ch.name.split(' ', limit = 2)
        if (parts.size == 2 && parts[0].any { it.code > 0x2E80 }) { out += norm(parts[0]); out += norm(parts[1]) }
        return out.filter { it.length >= 2 }.toSet()
    }

    private fun parseTime(s: String): Long? {
        val t = s.trim()
        for (f in listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss")) {
            try { return SimpleDateFormat(f, Locale.US).parse(t)?.time } catch (_: Exception) {}
        }
        return null
    }

    /** Downloads the guides and returns schedules keyed by our channel key. */
    fun download(channels: List<Channel>, status: (String) -> Unit): Map<String, List<Prog>> {
        val byAlias = HashMap<String, MutableList<String>>()
        channels.forEach { ch -> aliases(ch).forEach { a -> byAlias.getOrPut(a) { mutableListOf() } += ch.key } }

        val now = System.currentTimeMillis()
        val from = now - TimeUnit.HOURS.toMillis(3)
        val to = now + TimeUnit.DAYS.toMillis(4)
        val result = HashMap<String, MutableList<Prog>>()

        SOURCES.forEachIndexed { i, url ->
            status(Lang.t("📅 下載節目表 ", "📅 Downloading TV guide ") + "${i + 1}/${SOURCES.size}…")
            try {
                Net.open(url, null, 15000, 60000) { _, _, input, _ ->
                    if (input != null) parse(GZIPInputStream(BufferedInputStream(input, 65536)), byAlias, from, to, result)
                }
            } catch (_: Exception) { /* this country's guide is unavailable; skip */ }
        }
        return result.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start }.take(150) }
    }

    private fun parse(
        input: InputStream, byAlias: Map<String, List<String>>, from: Long, to: Long,
        out: MutableMap<String, MutableList<Prog>>,
    ) {
        val p = Xml.newPullParser()
        p.setInput(input, "UTF-8")
        val idToKeys = HashMap<String, List<String>>()   // xmltv channel id -> our keys
        var chId: String? = null
        var progKeys: List<String>? = null
        var start = 0L; var stop = 0L; var title: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "channel" -> {
                        chId = p.getAttributeValue(null, "id")
                        chId?.let { id -> byAlias[norm(id)]?.let { idToKeys[id] = it } }
                    }
                    "display-name" -> chId?.let { id ->
                        val keys = byAlias[norm(p.nextText())]
                        if (keys != null) idToKeys[id] = (idToKeys[id] ?: emptyList()) + keys
                    }
                    "programme" -> {
                        val keys = idToKeys[p.getAttributeValue(null, "channel") ?: ""]
                        progKeys = null
                        if (keys != null) {
                            start = parseTime(p.getAttributeValue(null, "start") ?: "") ?: 0L
                            stop = parseTime(p.getAttributeValue(null, "stop") ?: "") ?: 0L
                            if (stop > from && start < to) { progKeys = keys; title = null }
                        }
                    }
                    "title" -> if (progKeys != null && title == null) title = p.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "channel" -> chId = null
                    "programme" -> {
                        val keys = progKeys
                        val t = title
                        if (keys != null && !t.isNullOrEmpty()) {
                            keys.distinct().forEach { k -> out.getOrPut(k) { mutableListOf() } += Prog(start, stop, t) }
                        }
                        progKeys = null
                    }
                }
            }
            ev = p.next()
        }
    }
}
