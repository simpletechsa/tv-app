package tw.simpletech.tvapp

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Reads M3U playlists. */
object M3u {
    data class Entry(val id: String, val name: String, val logo: String, val cats: String, val stream: Stream)

    private val attr = Regex("""([\w-]+)="([^"]*)"""")
    private val tags = Regex("""\s*\[[^\]]*]""")
    private val quality = Regex("""\s*\(\d{3,4}[pi]\)""")

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var info: String? = null
        var ref: String? = null
        var ua: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    info = line; ref = null; ua = null
                    attr.findAll(line).forEach { m ->
                        when (m.groupValues[1].lowercase()) {
                            "http-referrer" -> ref = m.groupValues[2]
                            "http-user-agent" -> ua = m.groupValues[2]
                        }
                    }
                }
                line.startsWith("#EXTVLCOPT:http-referrer=") -> ref = line.substringAfter("=")
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> ua = line.substringAfter("=")
                line.startsWith("#") -> {}
                line.startsWith("http") -> {
                    val i = info ?: continue
                    val a = attr.findAll(i).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val commaFrom = i.lastIndexOf('"').coerceAtLeast(0)
                    val comma = i.indexOf(',', commaFrom)
                    val rawName = if (comma >= 0) i.substring(comma + 1) else ""
                    val geo = rawName.contains("geo-blocked", true)
                    val name = rawName.replace(tags, "").replace(quality, "").trim()
                        .ifEmpty { a["tvg-name"] ?: "Unknown" }
                    out.add(
                        Entry(
                            a["tvg-id"] ?: "", name, a["tvg-logo"] ?: "", a["group-title"] ?: "",
                            Stream(line, ref?.ifEmpty { null }, ua?.ifEmpty { null }, geo),
                        )
                    )
                    info = null
                }
            }
        }
        return out
    }
}

/** Collects channels from free public playlists and sorts them into groups. */
object Builder {
    private const val BASE = "https://iptv-org.github.io/iptv"

    /** The ~20 free-to-air Taiwan channels (matched on the English or Chinese name). */
    val MUST = listOf(
        "台視 TTV" to "^(ttv|台視|臺視)( hd| main)?$",
        "台視新聞 TTV News" to "ttv news|台視新聞|臺視新聞",
        "台視財經 TTV Finance" to "ttv finance|台視財經|臺視財經",
        "台視綜合 TTV Variety" to "ttv variety|台視綜合|臺視綜合",
        "中視 CTV" to "^(ctv|中視)( hd| main)?$",
        "中視新聞 CTV News" to "ctv news|中視新聞",
        "中視菁采 CTV Bravo" to "ctv bravo|中視菁采",
        "中視經典 CTV Classic" to "ctv classic|中視經典",
        "華視 CTS" to "^(cts|華視)( hd| main)?$",
        "華視新聞 CTS News" to "cts news|華視新聞",
        "華視教育體育文化 CTS Education" to "cts education|華視教育",
        "民視 FTV" to "^(ftv|民視)( hd| main)?$",
        "民視新聞 FTV News" to "ftv news|民視新聞",
        "民視第一台 FTV One" to "ftv one|民視第一台",
        "民視台灣台 FTV Taiwan" to "ftv taiwan|民視台灣台|民視臺灣台",
        "公視 PTS" to "^(pts|公視|公共電視)( hd| main)?$",
        "公視台語台 PTS Taigi" to "pts taigi|公視台語|公視臺語",
        "公視3 PTS 3" to "pts ?3|公視 ?3",
        "客家電視 Hakka TV" to "hakka|客家電視",
        "原住民族電視台 TITV" to "titv|原住民|indigenous",
        "國會頻道 Parliamentary" to "國會頻道|parliament",
        "大愛一台 DaAi 1" to "^(daai ?(1|tv)|大愛一?台)$",
        "大愛二台 DaAi 2" to "^(daai ?2|大愛二台)$",
    ).map { it.first to Regex(it.second, RegexOption.IGNORE_CASE) }

    private val SKIP = listOf("religious", "shop", "xxx")

    /** Never add these channels (by request). */
    private val BANNED = Regex("""中天|\bcti\b""", RegexOption.IGNORE_CASE)

    private val RUGBY = Regex("""rugby|\bnrl\b""", RegexOption.IGNORE_CASE)
    private val mustNames = MUST.map { it.first }

    fun flag(cc: String): String {
        val c = if (cc == "uk") "GB" else cc.uppercase()
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    fun countryName(cc: String): String {
        if (cc.length != 2) return "?"
        val c = if (cc == "uk") "GB" else cc.uppercase()
        return java.util.Locale("", c).getDisplayCountry(java.util.Locale.ENGLISH).ifEmpty { c }
    }

    /** Position in the free-to-air list (for sorting the Taiwan folder), or -1. */
    fun mustIndex(name: String) = mustNames.indexOf(name)

    private fun base(id: String) = id.substringBefore('@')
    fun country(id: String) = base(id).substringAfterLast('.', "").lowercase()
    private const val FREE_TV = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    /** Free-TV has no categories: guess the folder from the channel name. */
    private fun byName(n: String): String {
        val l = n.lowercase()
        return when {
            l.contains("news") -> Groups.NEWS
            l.contains("sport") -> Groups.SPORT
            l.contains("kids") || l.contains("junior") || l.contains("cartoon") -> Groups.KIDS
            l.contains("movie") || l.contains("cinema") || l.contains("film") -> Groups.MOVIES
            l.contains("music") -> Groups.MUSIC
            else -> Groups.ENT
        }
    }

    /** Official free YouTube lives of Taiwan channels (own folder, played inside the app). */
    private val YOUTUBE = listOf(
        "台視新聞 TTV News" to listOf("https://www.youtube.com/c/twlivenews/live", "https://www.youtube.com/c/ttvnewsview/live", "https://www.youtube.com/watch?v=xL0ch83RAK8", "https://www.youtube.com/watch?v=9iRAqBMakXY"),
        "中視新聞 CTV News" to listOf("https://www.youtube.com/c/twctvnews/live", "https://www.youtube.com/@chinatvnews/live", "https://www.youtube.com/watch?v=TCnaIE_SAtM", "https://www.youtube.com/watch?v=baDV1O5EBP0"),
        "華視新聞 CTS News" to listOf("https://www.youtube.com/@CtsTw/live", "https://www.youtube.com/watch?v=wM0g8EoUZ_E"),
        "民視新聞 FTV News" to listOf("https://www.youtube.com/@FTV_News/live", "https://www.youtube.com/watch?v=ylYJSBUgaMA"),
        "公視 PTS" to listOf("https://www.youtube.com/@PNNPTS/live", "https://www.youtube.com/watch?v=quwqlazU-c8"),
        "客家電視 Hakka TV" to listOf("https://www.youtube.com/@HakkaTV/live", "https://www.youtube.com/@hakkatv/streams"),
        "原住民族電視台 TITV" to listOf("https://www.youtube.com/@TITV/live", "https://www.youtube.com/@titv8932/live"),
        "大愛一台 DaAi 1" to listOf("https://www.youtube.com/watch?v=MIqUplvSRWA", "https://www.youtube.com/@DaAiVideo/live"),
        "大愛二台 DaAi 2" to listOf("https://www.youtube.com/watch?v=OKvWtVoDR8I"),
        "三立新聞 SET News" to listOf("https://www.youtube.com/watch?v=oZdzzvxTfUY", "https://www.youtube.com/@setnews/live"),
        "三立iNEWS SET iNews" to listOf("https://www.youtube.com/watch?v=CKjSm5ZeehE", "https://www.youtube.com/@setinews/live"),
        "鏡新聞 Mirror News" to listOf("https://www.youtube.com/watch?v=5n0y6b0Q25o", "https://www.youtube.com/@mnews-tw/live"),
        "寰宇新聞 Global News" to listOf("https://www.youtube.com/watch?v=6IquAgfvYmc"),
        "寰宇新聞台灣 Global News Taiwan" to listOf("https://www.youtube.com/watch?v=w87VGpgd90U"),
        "TVBS新聞 TVBS News" to listOf("https://www.youtube.com/watch?v=m_dhMSvUCIc"),
        "東森新聞 EBC News" to listOf("https://www.youtube.com/watch?v=LuQ4S-i5zoE"),
        "東森財經 EBC Finance" to listOf("https://www.youtube.com/watch?v=WHEPzbFA3hw"),
    )


    private fun skip(e: M3u.Entry) = SKIP.any { e.cats.contains(it, true) } || BANNED.containsMatchIn(e.name)

    private fun load(path: String): List<M3u.Entry> =
        try { M3u.parse(Net.getText("$BASE/$path")) } catch (e: Exception) { emptyList() }

    fun build(customUrls: List<String>, status: (String) -> Unit): MutableList<Channel> {
        status(Lang.t("下載頻道清單…", "Downloading channel lists…"))
        val tw = M3u.parse(Net.getText("$BASE/countries/tw.m3u"))   // must work, else error
        val za = load("countries/za.m3u")
        val adult = load("categories/xxx.m3u")
        // Second source: Free-TV (only channels that are officially free). Grouped by country.
        val freeTv = try { M3u.parse(Net.getText(FREE_TV)) } catch (e: Exception) { emptyList() }
        val sports = load("categories/sports.m3u")
        val eng = load("languages/eng.m3u")
        val zho = load("languages/zho.m3u")
        val engIds = eng.map { base(it.id) }.filter { it.isNotEmpty() }.toSet()
        val zhoIds = zho.map { base(it.id) }.filter { it.isNotEmpty() }.toSet()

        val map = LinkedHashMap<String, Channel>()
        fun add(e: M3u.Entry, group: String, name: String = e.name, keyPrefix: String = "") {
            val k = keyPrefix + base(e.id).ifEmpty { e.name.lowercase() }
            val ch = map.getOrPut(k) { Channel(k, name, group, e.logo) }
            if (ch.streams.none { it.url == e.stream.url }) ch.streams.add(e.stream)
            if (ch.logo.isEmpty()) ch.logo = e.logo
        }
        fun rugby(e: M3u.Entry) = RUGBY.containsMatchIn(e.name)

        // 1. Taiwan (all; "Taiwan only" links are fine because the check runs in Taiwan)
        for (e in tw) {
            if (skip(e)) continue
            val must = MUST.firstOrNull { m -> m.second.containsMatchIn(e.name) }
            if (must != null) add(e, Groups.TW, must.first) else add(e, Groups.TW)
        }
        // 1c. South Africa: its own folder (rugby still goes to the rugby folder)
        for (e in za) {
            if (skip(e)) continue
            when {
                rugby(e) -> add(e, Groups.RUGBY)
                e.stream.geo -> {}
                else -> add(e, Groups.ZA)
            }
        }
        // 1d. Free-TV: Taiwan, South Africa and the English-speaking countries (no YouTube links)
        for (e in freeTv) {
            if (skip(e) || e.stream.geo || e.stream.url.contains("youtu")) continue
            val c = e.cats.lowercase()
            when {
                rugby(e) -> add(e, Groups.RUGBY)
                c.contains("taiwan") -> add(e, Groups.TW,
                    MUST.firstOrNull { m -> m.second.containsMatchIn(e.name) }?.first ?: e.name)
                c.contains("south africa") -> add(e, Groups.ZA)
                listOf("uk", "united kingdom", "usa", "united states", "australia", "new zealand")
                    .any { c == it || c.startsWith("$it ") || c.contains(it) && it.length > 3 } -> add(e, byName(e.name))
            }
        }
        // 1e. YouTube Taiwan folder (official lives; 中天 never included)
        for ((name, urls) in YOUTUBE) {
            if (BANNED.containsMatchIn(name)) continue
            val ch = Channel("yt:$name", name, Groups.YT)
            urls.forEach { u -> ch.streams.add(Stream("yt:$u")) }
            map["yt:$name"] = ch
        }
        // 1f. Adult folder (only visible when Admin is on)
        for (e in adult) if (!BANNED.containsMatchIn(e.name)) add(e, Groups.ADULT, keyPrefix = "x:")
        // 2. Your own playlists
        for (url in customUrls) {
            val list = try { M3u.parse(Net.getText(url)) } catch (e: Exception) { emptyList() }
            for (e in list) if (!BANNED.containsMatchIn(e.name)) add(e, Groups.MY, keyPrefix = "my:")
        }
        // 3. Rugby + sports in English or Chinese. Links marked geo-blocked need a VPN -> skipped.
        for (e in sports) {
            if (skip(e) || country(e.id) == "tw") continue
            val b = base(e.id)
            when {
                rugby(e) -> add(e, Groups.RUGBY)          // VPN-only rugby/sports are kept, with the country shown
                b in engIds || b in zhoIds -> add(e, Groups.SPORT)
            }
        }
        // 3b. Rugby from any Chinese list too
        for (e in zho) if (!skip(e) && rugby(e)) add(e, Groups.RUGBY)
        // 4. English channels from your chosen countries only, sorted into folders by type
        for (e in eng) {
            if (skip(e)) continue
            val cc = country(e.id)
            when {
                rugby(e) -> add(e, Groups.RUGBY)
                e.stream.geo -> {}
                cc in Groups.COUNTRIES -> add(e, Groups.byCategory(e.cats))
            }
        }
        // Logo backup source: iptv-org's logo database, for channels that came without a logo
        if (map.values.any { it.logo.isEmpty() }) {
            try {
                val arr = org.json.JSONArray(Net.getText("https://iptv-org.github.io/api/logos.json"))
                val byId = HashMap<String, String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("channel"); val url = o.optString("url")
                    if (id.isNotEmpty() && url.startsWith("http")) byId.putIfAbsent(id, url)
                }
                map.values.filter { it.logo.isEmpty() }.forEach { ch -> byId[base(ch.key.removePrefix("my:"))]?.let { ch.logo = it } }
            } catch (_: Exception) { /* logos are optional */ }
        }
        return map.values.toMutableList()
    }
}

/** Tests every channel's links and keeps the best working one first. */
object Checker {
    private const val KEEP_DAYS = 7L

    /**
     * [vpn] = a VPN is on. Then only rugby is really tested (and marked as needing the VPN);
     * every other channel keeps its last result from a normal (no VPN) check.
     */
    fun run(channels: List<Channel>, previous: Map<String, Channel>, vpn: Boolean = false, progress: (Int, Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(12)
        val done = AtomicInteger()
        val now = System.currentTimeMillis()
        for (ch in channels) {
            pool.execute {
                try {
                    if (vpn) checkWithVpn(ch, previous[ch.key], now) else checkOne(ch, previous[ch.key], now)
                } catch (e: Exception) { ch.status = "dead" }
                progress(done.incrementAndGet(), channels.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(3, TimeUnit.HOURS)
    }

    private fun promote(ch: Channel, s: Stream) {
        ch.streams.remove(s); ch.streams.add(0, s)
    }

    private fun copyPrev(ch: Channel, prev: Channel) {
        ch.streams.clear(); ch.streams.addAll(prev.streams.filter { !it.isYouTube || ch.group == Groups.YT })
        if (ch.streams.isEmpty()) { ch.status = "dead"; return }
        ch.status = prev.status; ch.lastOk = prev.lastOk
    }

    private fun checkWithVpn(ch: Channel, prev: Channel?, now: Long) {
        if (ch.group !in Groups.VPN_OK) {
            if (prev != null) copyPrev(ch, prev) else ch.status = "dead"
            return
        }
        for (s in ch.streams.toList()) {
            if (!s.isYouTube && Net.check(s, relaxed = true) == "ok") {
                promote(ch, s)
                ch.status = if (prev?.status == "ok") "ok" else "vpn"
                ch.lastOk = now
                return
            }
        }
        if (prev != null) copyPrev(ch, prev) else ch.status = "dead"
    }

    private fun checkOne(ch: Channel, prev: Channel?, now: Long) {
        // try last working link first
        if (prev != null && prev.status != "dead") {
            prev.streams.firstOrNull()?.takeIf { !it.isYouTube || ch.group == Groups.YT }?.let { p ->
                ch.streams.removeAll { it.url == p.url }
                ch.streams.add(0, p)
            }
        }
        var geo: Stream? = null
        for (s in ch.streams.toList()) {
            if (s.isYouTube) {
                val id = Net.youTubeLive(s.url) ?: continue
                ch.streams.remove(s); ch.streams.add(0, s.copy(extra = id))
                ch.status = "ok"; ch.lastOk = now; return
            }
            // gentle test for every channel (playlist answers = OK): find as many as possible
            when (Net.check(s, relaxed = true)) {
                "ok" -> { promote(ch, s); ch.status = "ok"; ch.lastOk = now; return }
                "geo" -> if (geo == null) geo = s
            }
        }
        // Rugby that only works with a VPN: keep it and show which country to pick
        if (ch.group in Groups.VPN_OK && geo != null) {
            promote(ch, geo); ch.status = "vpn"; return
        }
        if (ch.group in Groups.VPN_OK && prev?.status == "vpn") { copyPrev(ch, prev); return }
        // Free-to-air Taiwan channels: keep the last working link for a few days
        if (ch.group == Groups.TW && Builder.mustIndex(ch.name) >= 0 && prev != null && prev.lastOk > 0 && prev.streams.isNotEmpty() &&
            now - prev.lastOk < TimeUnit.DAYS.toMillis(KEEP_DAYS)
        ) {
            ch.streams.clear(); ch.streams.addAll(prev.streams.filter { !it.isYouTube })
            if (ch.streams.isNotEmpty()) { ch.status = "kept"; ch.lastOk = prev.lastOk; return }
        }
        ch.status = "dead"
    }
}
