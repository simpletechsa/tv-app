package tw.simpletech.tvapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves channels, favorites and the TV guide on the device. */
object Store {
    @Volatile var channels: List<Channel> = emptyList()
    @Volatile var epg: Map<String, List<Prog>> = emptyMap()
    private var favs: MutableSet<String> = mutableSetOf()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    private fun writeAtomic(ctx: Context, name: String, text: String) {
        val tmp = File(ctx.filesDir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(ctx.filesDir, name))
    }

    // ------------------------------------------------------------ channels
    fun load(ctx: Context) {
        channels = try {
            val arr = JSONArray(File(ctx.filesDir, "channels.json").readText())
            (0 until arr.length()).map { Channel.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        favs = (prefs(ctx).getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet()
        loadEpg(ctx)
    }

    fun save(ctx: Context, list: List<Channel>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeAtomic(ctx, "channels.json", arr.toString())
        channels = list
        prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
    }

    fun lastCheck(ctx: Context) = prefs(ctx).getLong("lastCheck", 0L)

    fun customUrls(ctx: Context): List<String> =
        (prefs(ctx).getString("custom", "") ?: "").lines().map { it.trim() }.filter { it.startsWith("http") }

    fun setCustomUrls(ctx: Context, text: String) =
        prefs(ctx).edit().putString("custom", text).apply()

    // ------------------------------------------------------------ favorites
    fun isFav(ch: Channel) = ch.key in favs

    /** Returns true if the channel is now a favorite. */
    fun toggleFav(ctx: Context, ch: Channel): Boolean {
        val now = if (favs.remove(ch.key)) false else { favs.add(ch.key); true }
        prefs(ctx).edit().putStringSet("favs", HashSet(favs)).apply()
        return now
    }

    // ------------------------------------------------------------ groups
    fun groups(): List<Pair<String, Int>> {
        val counts = channels.groupingBy { it.group }.eachCount().toMutableMap()
        val favCount = channels.count { isFav(it) }
        if (favCount > 0) counts[Groups.FAV] = favCount
        return counts.toList().sortedWith(compareBy({ Groups.order(it.first) }, { it.first }))
    }

    fun inGroup(g: String): List<Channel> {
        val list = if (g == Groups.FAV) channels.filter { isFav(it) } else channels.filter { it.group == g }
        return list.sortedWith(compareBy(
            { Builder.mustIndex(it.name).let { i -> if (i < 0) 999 else i } },
            { it.status != "ok" }, { it.name.lowercase() }))
    }

    // ------------------------------------------------------------ TV guide
    fun saveEpg(ctx: Context, data: Map<String, List<Prog>>) {
        val o = JSONObject()
        data.forEach { (k, list) ->
            o.put(k, JSONArray().apply {
                list.forEach { put(JSONArray().put(it.start).put(it.stop).put(it.title)) }
            })
        }
        writeAtomic(ctx, "epg.json", o.toString())
        epg = data
        prefs(ctx).edit().putLong("lastEpg", System.currentTimeMillis()).apply()
    }

    private fun loadEpg(ctx: Context) {
        epg = try {
            val o = JSONObject(File(ctx.filesDir, "epg.json").readText())
            val m = HashMap<String, List<Prog>>()
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                m[k] = (0 until a.length()).map {
                    val p = a.getJSONArray(it); Prog(p.getLong(0), p.getLong(1), p.getString(2))
                }
            }
            m
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun lastEpg(ctx: Context) = prefs(ctx).getLong("lastEpg", 0L)

    /** Programs from "now" onwards: first one is on now (if any). */
    fun guide(ch: Channel, count: Int): List<Prog> {
        val now = System.currentTimeMillis()
        return (epg[ch.key] ?: return emptyList()).filter { it.stop > now }.take(count)
    }
}
