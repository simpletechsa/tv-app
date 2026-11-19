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

    /** Countries we want from the second guide source (globetvapp/epg on GitHub). */
    private val GLOBE_COUNTRIES = listOf(
        "taiwan", "south africa", "southafrica", "united kingdom", "uk", "usa", "united states",
        "australia", "new zealand", "newzealand", "korea", "south korea", "japan",
    )

    /**
     * Second TV guide source: globetvapp/epg (folders by country, e.g. Australia/australia1.xml).
     * The file list is read from GitHub each time, so new files are picked up automatically.
     */
    private fun globeTvFiles(): List<String> = try {
        val api = "https://api.github.com/repos/globetvapp/epg/contents"
        val folders = org.json.JSONArray(Net.getText(api))
        val out = ArrayList<String>()
        for (i in 0 until folders.length()) {
            val f = folders.getJSONObject(i)
            if (f.optString("type") != "dir") continue
            val name = f.optString("name")
            if (name.lowercase().replace('_', ' ').replace('-', ' ') !in GLOBE_COUNTRIES) continue
            try {
                val files = org.json.JSONArray(Net.getText("$api/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")))
                var taken = 0
                for (j in 0 until files.length()) {
                    val file = files.getJSONObject(j)
                    val fn = file.optString("name")
                    val dl = file.optString("download_url")
                    if ((fn.endsWith(".xml") || fn.endsWith(".xml.gz")) && dl.startsWith("http") && taken < 4) {
                        out += dl; taken++
                    }
                }
            } catch (_: Exception) { }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }

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
    /** Channel logos found in the guide files (our channel key -> picture link). */
    @Volatile var lastLogos: Map<String, String> = emptyMap()

    fun download(channels: List<Channel>, status: (String) -> Unit): Map<String, List<Prog>> {
        val logos = HashMap<String, String>()
        val byAlias = HashMap<String, MutableList<String>>()
        channels.forEach { ch -> aliases(ch).forEach { a -> byAlias.getOrPut(a) { mutableListOf() } += ch.key } }

        val now = System.currentTimeMillis()
        val from = now - TimeUnit.HOURS.toMillis(3)
        val to = now + TimeUnit.DAYS.toMillis(4)
        val result = HashMap<String, MutableList<Prog>>()

        val all = SOURCES + globeTvFiles()
        all.forEachIndexed { i, url ->
            status(Lang.t("📅 下載節目表 ", "📅 Downloading TV guide ") + "${i + 1}/${all.size}…")
            try {
                Net.open(url, null, 15000, 60000) { _, _, input, _ ->
                    if (input != null) {
                        val buf = BufferedInputStream(input, 65536)
                        val stream = if (url.endsWith(".gz")) GZIPInputStream(buf) else buf
                        parse(stream, byAlias, from, to, result, logos)
                    }
                }
            } catch (_: Exception) { /* this guide file is unavailable; skip */ }
        }
        lastLogos = logos
        return result.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start }.take(150) }
    }

    private fun parse(
        input: InputStream, byAlias: Map<String, List<String>>, from: Long, to: Long,
        out: MutableMap<String, MutableList<Prog>>,
        logos: MutableMap<String, String>,
    ) {
        var icon: String? = null
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
                    "icon" -> if (chId != null) icon = p.getAttributeValue(null, "src")
                    "channel" -> {
                        icon = null
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
                    "channel" -> {
                        val id = chId
                        val src = icon
                        if (id != null && !src.isNullOrBlank() && src.startsWith("http")) {
                            idToKeys[id]?.forEach { k -> logos.putIfAbsent(k, src) }
                        }
                        chId = null
                    }
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
