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
            "geo" -> "  (VPN?)"
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

object Groups {
    const val MUST = "⭐ 台灣無線台 Taiwan Free-to-Air"
    const val TW = "🇹🇼 台灣 Taiwan"
    const val MY = "📌 我的清單 My playlists"
    const val RUGBY = "🏉 橄欖球 Rugby"
    const val SPORT = "⚽ 體育 Sports"
    const val UNSURE = "❓ 語言未確認 Language unconfirmed"

    private val fixed = listOf(MUST, TW, MY, RUGBY, SPORT)

    /** Sort order: fixed groups first, English countries next, unconfirmed last. */
    fun order(g: String): Pair<Int, String> = when {
        g in fixed -> fixed.indexOf(g) to ""
        g == UNSURE -> 99 to ""
        g.contains("United States") -> 10 to g
        g.contains("United Kingdom") -> 11 to g
        g.contains("South Africa") -> 12 to g
        else -> 20 to g
    }
}
