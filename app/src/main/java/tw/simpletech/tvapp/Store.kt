package tw.simpletech.tvapp

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Saves the checked channel list on the device. */
object Store {
    @Volatile var channels: List<Channel> = emptyList()

    private fun file(ctx: Context) = File(ctx.filesDir, "channels.json")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        channels = try {
            val arr = JSONArray(file(ctx).readText())
            (0 until arr.length()).map { Channel.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, list: List<Channel>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "channels.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
        channels = list
        prefs(ctx).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
    }

    fun lastCheck(ctx: Context) = prefs(ctx).getLong("lastCheck", 0L)

    fun customUrls(ctx: Context): List<String> =
        (prefs(ctx).getString("custom", "") ?: "").lines().map { it.trim() }.filter { it.startsWith("http") }

    fun setCustomUrls(ctx: Context, text: String) =
        prefs(ctx).edit().putString("custom", text).apply()

    fun groups(): List<Pair<String, Int>> =
        channels.groupingBy { it.group }.eachCount().toList()
            .sortedWith(compareBy({ Groups.order(it.first).first }, { Groups.order(it.first).second }))

    fun inGroup(g: String) = channels.filter { it.group == g }
        .sortedWith(compareBy({ it.status != "ok" }, { it.name.lowercase() }))
}
