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
    /**
     * Guide files, best first. A channel takes its guide from the first file that has it.
     * 1) my-epg: 119 Taiwan channels, Traditional Chinese titles, updated every 6 hours (GitHub).
     * 2) epg.pw: free guides per country.
     * 3) epgshare01 + globetvapp: wider backup.
     */
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/XinMan939/my-epg/gh-pages/epg.xml.gz",
    ) + listOf("TW", "ZA", "GB", "US", "AU", "NZ").map { "https://epg.pw/xmltv/epg_$it.xml.gz" } +
        listOf("TW1", "ZA1", "UK1", "US2", "AU1", "NZ1", "KR1", "JP1")
            .map { "https://epgshare01.online/epgshare01/epg_ripper_$it.xml.gz" }

    private val drop = Regex("""\b(hd|sd|fhd|uhd|4k|tv|channel|east|west)\b""")

    /** Traditional -> Simplified pairs, only used to match names (what you see stays Traditional). */
    private val T2S: Map<Char, Char> = "視视聞闻華华臺台東东緯纬來来樂乐戲戏劇剧綜综電电灣湾體体國国會会經经財财衛卫愛爱爾尔達达鏡镜間间頻频業业衆众龍龙運运動动際际語语號号藝艺專专實实紀纪錄录鳳凤亞亚歐欧無无線线兒儿畫画廳厅網网寶宝貝贝時时學学習习傳传統统園园遊游鬥斗爭争雙双黃黄陽阳開开發发現现歷历書书師师員员門门風风雲云頭头條条報报導导點点創创區区權权證证貨货幣币銀银務务機机場场億亿萬万個个們们這这裡里說说話话讀读寫写飛飞車车馬马魚鱼鳥鸟豬猪雞鸡麼么為为與与對对後后從从當当還还過过關关隊队賽赛籃篮盃杯錦锦標标聯联盟盟壹壹採采環环樂乐韓韩僑侨灣湾窩窝選选購购物物".let { s ->
        (0 until s.length - 1 step 2).associate { s[it] to s[it + 1] }
    }
    private val NUM = mapOf('①' to '1', '②' to '2', '③' to '3', 'Ⅰ' to '1', 'Ⅱ' to '2', 'Ⅲ' to '3', 'Ⅳ' to '4')

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

    /** Simplify a name for matching: "BBC News HD" -> "bbcnews", "華視新聞台" / "华视新闻" -> "华视新闻". */
    fun norm(s: String): String {
        var low = s.lowercase().substringBefore('@')
            .replace(Regex("""\.[a-z]{2}$"""), "")          // remove ".uk" style country end
            .replace('.', ' ').replace('_', ' ')
        low = low.map { NUM[it] ?: T2S[it] ?: it }.joinToString("").replace("一台", "1").replace("二台", "2")
        var r = drop.replace(low, " ").filter { it.isLetterOrDigit() }
        if (r.length > 3 && r.any { it.code > 0x2E80 }) {
            for (suf in listOf("电视台", "台")) if (r.endsWith(suf) && r.length - suf.length >= 2) { r = r.dropLast(suf.length); break }
        }
        return r
    }

    private fun aliases(ch: Channel): Set<String> {
        val out = HashSet<String>()
        out += norm(ch.name.substringBefore(" · "))                  // rugby rows: "Rugby Pass TV · Pluto"
        out += norm(ch.key.removePrefix("my:").substringBefore('#'))
        // "台視新聞 TTV News" -> also "台視新聞" and "TTV News"
        val parts = ch.name.split(' ', limit = 2)
        if (parts.size == 2 && parts[0].any { it.code > 0x2E80 }) { out += norm(parts[0]); out += norm(parts[1]) }
        return out.filter { it.length >= 2 }.toSet()
    }

    /** Second try, only if nothing matched exactly: "東森財經" -> "東森財經新聞". */
    private fun fallbacks(exact: Set<String>) =
        exact.filter { a -> a.any { it.code > 0x2E80 } && !a.endsWith("新闻") }.map { it + "新闻" }.toSet()

    private fun parseTime(s: String): Long? {
        val t = s.trim()
        for (f in listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss")) {
            try { return SimpleDateFormat(f, Locale.US).parse(t)?.time } catch (_: Exception) {}
        }
        return null
    }

    /** Channel logos found in the guide files (our channel key -> picture link). */
    @Volatile var lastLogos: Map<String, String> = emptyMap()

    /** Downloads the guides and returns schedules keyed by our channel key. */
    fun download(channels: List<Channel>, status: (String) -> Unit): Map<String, List<Prog>> {
        val logos = HashMap<String, String>()
        val exact = HashMap<String, MutableList<String>>()
        val second = HashMap<String, MutableList<String>>()
        channels.forEach { ch ->
            val a = aliases(ch)
            a.forEach { exact.getOrPut(it) { mutableListOf() } += ch.key }
            fallbacks(a).forEach { second.getOrPut(it) { mutableListOf() } += ch.key }
        }

        val now = System.currentTimeMillis()
        val from = now - TimeUnit.HOURS.toMillis(3)
        val to = now + TimeUnit.DAYS.toMillis(4)
        val result = HashMap<String, List<Prog>>()

        val all = SOURCES + globeTvFiles()
        all.forEachIndexed { i, url ->
            status(Lang.t("📅 下載節目表 ", "📅 Downloading TV guide ") + "${i + 1}/${all.size}…")
            val got = HashMap<String, MutableList<Prog>>()
            try {
                Net.open(url, null, 15000, 90000) { _, _, input, _ ->
                    if (input != null) {
                        val buf = BufferedInputStream(input, 65536)
                        val stream = if (url.endsWith(".gz")) GZIPInputStream(buf) else buf
                        parse(stream, exact, second, from, to, got, logos)
                    }
                }
            } catch (_: Exception) { /* this guide file is unavailable or cut off; keep what was read */ }
            // first file that has a channel wins, so two guides are never mixed
            got.forEach { (k, v) ->
                if (v.isNotEmpty() && !result.containsKey(k))
                    result[k] = v.sortedBy { it.start }.distinctBy { it.start }.take(150)
            }
        }
        lastLogos = logos
        return result
    }

    private fun parse(
        input: InputStream, exact: Map<String, List<String>>, second: Map<String, List<String>>,
        from: Long, to: Long,
        out: MutableMap<String, MutableList<Prog>>,
        logos: MutableMap<String, String>,
    ) {
        var icon: String? = null
        val p = Xml.newPullParser()
        p.setInput(input, "UTF-8")
        val exactIds = LinkedHashMap<String, LinkedHashSet<String>>()   // xmltv channel id -> our keys
        val secondIds = LinkedHashMap<String, LinkedHashSet<String>>()
        var idToKeys: Map<String, List<String>>? = null                  // fixed at the first programme
        var chId: String? = null
        var progKeys: List<String>? = null
        var start = 0L; var stop = 0L; var title: String? = null

        fun note(id: String, name: String) {
            val n = norm(name)
            exact[n]?.let { exactIds.getOrPut(id) { LinkedHashSet() } += it }
            second[n]?.let { secondIds.getOrPut(id) { LinkedHashSet() } += it }
        }

        /** Each of our channels gets one guide channel: an exact match first, the "…新聞" try only if none. */
        fun resolve(): Map<String, List<String>> {
            val taken = HashSet<String>()
            val m = HashMap<String, MutableList<String>>()
            for ((id, keys) in exactIds) for (k in keys) if (taken.add(k)) m.getOrPut(id) { mutableListOf() } += k
            for ((id, keys) in secondIds) for (k in keys) if (taken.add(k)) m.getOrPut(id) { mutableListOf() } += k
            return m
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "icon" -> if (chId != null) icon = p.getAttributeValue(null, "src")
                    "channel" -> {
                        icon = null
                        chId = p.getAttributeValue(null, "id")
                        chId?.let { note(it, it) }
                    }
                    "display-name" -> chId?.let { id -> note(id, p.nextText()) }
                    "programme" -> {
                        val map = idToKeys ?: resolve().also { idToKeys = it }
                        val keys = map[p.getAttributeValue(null, "channel") ?: ""]
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
                            (exactIds[id] ?: emptySet<String>()).forEach { k -> logos.putIfAbsent(k, src) }
                        }
                        chId = null
                    }
                    "programme" -> {
                        val keys = progKeys
                        val t = title
                        if (keys != null && !t.isNullOrEmpty()) {
                            keys.forEach { k -> out.getOrPut(k) { mutableListOf() } += Prog(start, stop, t) }
                        }
                        progKeys = null
                    }
                }
            }
            ev = p.next()
        }
    }
}
