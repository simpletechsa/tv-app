package tw.simpletech.tvapp

import android.content.Context
import java.util.Locale

/** App language: Traditional Chinese or English. */
object Lang {
    @Volatile var zh = true
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val saved = prefs(ctx).getString("lang", null)
        zh = saved?.let { it == "zh" } ?: Locale.getDefault().language.startsWith("zh")
    }

    fun set(ctx: Context, chinese: Boolean) {
        zh = chinese
        prefs(ctx).edit().putString("lang", if (chinese) "zh" else "en").apply()
    }

    /** Pick the text for the current language. */
    fun t(zhText: String, enText: String) = if (zh) zhText else enText

    private fun cjk(s: String) = s.any { it.code in 0x3400..0x9FFF }

    /**
     * For two-language names like "🇹🇼 台灣 Taiwan" or "台視新聞 TTV News":
     * keeps the leading emoji and only the words of the current language.
     */
    fun pick(s: String): String {
        val toks = s.split(' ').filter { it.isNotEmpty() }
        if (toks.none { cjk(it) } || toks.all { cjk(it) || it.none { c -> c.isLetter() } }) return s
        val prefix = toks.takeWhile { t -> t.none { it.isLetterOrDigit() } }
        val rest = toks.drop(prefix.size)
        val chosen = if (zh) rest.filter { cjk(it) } else rest.filter { !cjk(it) }
        if (chosen.isEmpty()) return s
        return (prefix + chosen).joinToString(" ")
    }
}
