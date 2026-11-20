package tw.simpletech.tvapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves channels, favorites and the TV guide on the device. */
object Store {
    @Volatile var channels: List<Channel> = emptyList()
    @Volatile var epg: Map<String, List<Prog>> = emptyMap()
    @Volatile var guideLogos: Map<String, String> = emptyMap()

    /** Logo: from the channel list first, then from the TV guide. */
    fun logoFor(ch: Channel): String = ch.logo.ifEmpty { guideLogos[ch.key] ?: "" }

    fun saveLogos(ctx: Context, m: Map<String, String>) {
        if (m.isEmpty()) return
        val merged = guideLogos + m
        writeAtomic(ctx, "logos.json", JSONObject(merged).toString())
        guideLogos = merged
    }

    private fun loadLogos(ctx: Context) {
        guideLogos = try {
            val o = JSONObject(File(ctx.filesDir, "logos.json").readText())
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
    }
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
        loadLogos(ctx)
        loadAdmin(ctx)
    }

    /** Saves the list without counting it as the daily full check (used by single-folder refresh). */
    fun saveOnly(ctx: Context, list: List<Channel>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeAtomic(ctx, "channels.json", arr.toString())
        channels = list
    }

    fun save(ctx: Context, list: List<Channel>, viaVpn: Boolean = false) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        writeAtomic(ctx, "channels.json", arr.toString())
        channels = list
        // a VPN check only refreshes rugby, so it doesn't count as the daily full check
        prefs(ctx).edit().putLong(if (viaVpn) "lastVpnCheck" else "lastCheck", System.currentTimeMillis()).apply()
    }

    fun lastVpnCheck(ctx: Context) = prefs(ctx).getLong("lastVpnCheck", 0L)

    fun lastCheck(ctx: Context) = prefs(ctx).getLong("lastCheck", 0L)

    fun customUrls(ctx: Context): List<String> =
        (prefs(ctx).getString("custom", "") ?: "").lines().map { it.trim() }.filter { it.startsWith("http") }

    fun setCustomUrls(ctx: Context, text: String) =
        prefs(ctx).edit().putString("custom", text).apply()

    // ------------------------------------------------------------ admin (rated folder)
    @Volatile var adminOn = false
        private set

    fun loadAdmin(ctx: Context) { adminOn = prefs(ctx).getBoolean("admin", false) }
    // Only a scrambled fingerprint (hash) of the password is kept, never the password itself.
    private const val DEFAULT_KEY = "7c214c67cb512ff8beccab867f121cc921d55137c7dff2dbbd43c18811054568"

    private fun hash(pw: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(("stsa-tv:" + pw).toByteArray()).joinToString("") { "%02x".format(it) }

    /** Fixed admin password (cannot be changed). */
    fun checkPassword(pw: String) = hash(pw) == DEFAULT_KEY
    fun setAdmin(ctx: Context, on: Boolean) {
        adminOn = on
        prefs(ctx).edit().putBoolean("admin", on).apply()
    }

    // ------------------------------------------------------------ YouTube player choice
    /** null = built-in player; otherwise the package of the app chosen in Settings. */
    fun youTubeApp(ctx: Context): String? = prefs(ctx).getString("ytApp", null)
    fun setYouTubeApp(ctx: Context, pkg: String?) = prefs(ctx).edit().putString("ytApp", pkg).apply()

    // ------------------------------------------------------------ channel numbers on/off
    fun showNumbers(ctx: Context) = prefs(ctx).getBoolean("numbers", true)
    fun setShowNumbers(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("numbers", on).apply()

    /** Look: false = Springbok green & gold (default), true = Classic blue. */
    fun classic(ctx: Context) = prefs(ctx).getBoolean("classic", false)
    fun setClassic(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("classic", on).apply()

    // ------------------------------------------------------------ favorites
    fun isFav(ch: Channel) = ch.key in favs
    fun isFavKey(key: String) = key in favs

    /** Returns true if the channel is now a favorite. */
    fun toggleFav(ctx: Context, ch: Channel): Boolean {
        val now = if (favs.remove(ch.key)) false else { favs.add(ch.key); true }
        prefs(ctx).edit().putStringSet("favs", HashSet(favs)).apply()
        return now
    }

    // ------------------------------------------------------------ groups
    fun groups(): List<Pair<String, Int>> {
        val counts = channels.groupingBy { it.group }.eachCount().toMutableMap()
        if (!adminOn) counts.remove(Groups.ADULT) else counts.putIfAbsent(Groups.ADULT, 0)   // locked folder always shown to admin
        val favCount = channels.count { isFav(it) && it.group != Groups.ADULT }
        if (favCount > 0) counts[Groups.FAV] = favCount
        return counts.toList().sortedWith(compareBy({ Groups.order(it.first) }, { it.first }))
    }

    fun inGroup(g: String): List<Channel> {
        val list = if (g == Groups.FAV) channels.filter { isFav(it) && it.group != Groups.ADULT }
                   else channels.filter { it.group == g }
        return list.sortedWith(compareBy(
            { Builder.mustIndex(it.name).let { i -> if (i < 0) 999 else i } },
            { it.name.lowercase() }))
    }

    // ------------------------------------------------------------ TV guide
    fun saveEpg(ctx: Context, data: Map<String, List<Prog>>) {
        if (data.isEmpty() && epg.isNotEmpty()) return          // download failed: keep the old guide
        val o = JSONObject()
        data.forEach { (k, list) ->
            o.put(k, JSONArray().apply {
                list.forEach { put(JSONArray().put(it.start).put(it.stop).put(it.title)) }
            })
        }
        writeAtomic(ctx, "epg.json", o.toString())
        epg = data
        prefs(ctx).edit().putLong("lastEpg", System.currentTimeMillis()).putInt("epgVer", EPG_VER).apply()
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

    /** Raise when the guide sources change, so the guide is fetched again after the update. */
    const val EPG_VER = 2
    fun epgOld(ctx: Context) = prefs(ctx).getInt("epgVer", 1) < EPG_VER

    /** Programs from "now" onwards: first one is on now (if any). */
    fun guide(ch: Channel, count: Int): List<Prog> {
        val now = System.currentTimeMillis()
        return (epg[ch.key] ?: return emptyList()).filter { it.stop > now }.take(count)
    }
}
