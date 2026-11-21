package tw.simpletech.tvapp

import android.webkit.WebView

/**
 * YouTube channels you've already watched this app session stay open (paused) here,
 * so going back to them later doesn't start them again (no repeat ads).
 * Cleared when the app is closed.
 */
object YtPool {
    const val MAX = 6
    val map = LinkedHashMap<String, WebView>(8, 0.75f, true)

    /** Set by the screen that is showing; called when a YouTube live refuses to play. */
    @Volatile var onFailed: ((String) -> Unit)? = null

    /** Live video found when a channel was opened (page link -> video id, time), reused for 10 minutes. */
    val fresh = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
}
