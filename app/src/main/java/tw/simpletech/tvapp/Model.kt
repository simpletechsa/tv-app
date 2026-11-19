package tw.simpletech.tvapp

import org.json.JSONArray
import org.json.JSONObject

data class Stream(
    val url: String,
    val referrer: String? = null,
    val userAgent: String? = null,
    val geo: Boolean = false,
)

class Channel(
    val key: String,
    var name: String,
    var group: String,
    var logo: String = "",
    val streams: MutableList<Stream> = mutableListOf(),
    var status: String = "new",   // ok / geo / kept / dead / new
    var lastOk: Long = 0L,
) {
    val label: String
        get() = when (status) {
            "kept" -> "  ⟲"
            else -> ""
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", key); put("n", name); put("g", group); put("l", logo)
        put("s", status); put("t", lastOk)
        put("u", JSONArray().apply {
            streams.forEach { s ->
                put(JSONObject().apply {
                    put("u", s.url)
                    s.referrer?.let { put("r", it) }
                    s.userAgent?.let { put("a", it) }
                    put("g", s.geo)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Channel {
            val ch = Channel(
                o.getString("k"), o.getString("n"), o.getString("g"), o.optString("l"),
                status = o.optString("s", "ok"), lastOk = o.optLong("t"),
            )
            val arr = o.optJSONArray("u") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                ch.streams.add(
                    Stream(
                        s.getString("u"),
                        if (s.has("r")) s.getString("r") else null,
                        if (s.has("a")) s.getString("a") else null,
                        s.optBoolean("g"),
                    )
                )
            }
            return ch
        }
    }
}

data class Prog(val start: Long, val stop: Long, val title: String)

object Groups {
    const val FAV = "❤️ 我的最愛 Favorites"
    const val TW = "🇹🇼 台灣 Taiwan"
    const val MY = "📌 我的清單 My playlists"
    const val RUGBY = "🏉 橄欖球 Rugby"
    const val SPORT = "⚽ 體育 Sports"
    const val NEWS = "📰 新聞 News"
    const val MOVIES = "🎬 電影 Movies"
    const val SERIES = "📺 影集喜劇 Series & Comedy"
    const val KIDS = "🧸 兒童 Kids"
    const val DOC = "🌍 紀錄片 Documentary"
    const val MUSIC = "🎵 音樂 Music"
    const val LIFE = "🍳 生活旅遊 Lifestyle"
    const val ENT = "🎭 綜合娛樂 Entertainment"
    const val OTHER = "📂 其他 Other"

    /** English channels are kept only from these countries. */
    val COUNTRIES = setOf("za", "uk", "us", "au", "nz", "kr", "jp")

    private val fixed = listOf(FAV, TW, MY, RUGBY, SPORT, NEWS, MOVIES, SERIES, KIDS, DOC, MUSIC, LIFE, ENT, OTHER)

    fun order(g: String): Int = fixed.indexOf(g).let { if (it < 0) 99 else it }

    /** Picks a folder from iptv-org's category text, e.g. "News;General". */
    fun byCategory(cats: String): String {
        val c = cats.lowercase()
        return when {
            c.contains("sport") -> SPORT
            c.contains("kids") || c.contains("animation") -> KIDS
            c.contains("movie") -> MOVIES
            c.contains("series") || c.contains("comedy") -> SERIES
            c.contains("news") || c.contains("weather") || c.contains("business") || c.contains("legislative") -> NEWS
            c.contains("documentary") || c.contains("science") || c.contains("outdoor") -> DOC
            c.contains("music") -> MUSIC
            listOf("cooking", "travel", "lifestyle", "family", "auto").any { c.contains(it) } -> LIFE
            listOf("entertainment", "general", "culture", "classic", "education").any { c.contains(it) } -> ENT
            else -> OTHER
        }
    }
}
