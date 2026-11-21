package tw.simpletech.tvapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var info: TextView
    private lateinit var webBox: android.widget.FrameLayout

    private val webPool get() = YtPool.map
    private var webCurrent: String? = null
    private lateinit var gestures: GestureDetector
    private lateinit var overlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlayHint: TextView
    private lateinit var overlayList: RecyclerView
    private val overlayAdapter = OverlayAdapter()

    private var group = ""
    private lateinit var chans: List<Channel>
    private var index = 0
    private var streamIdx = 0
    private var forceHls = false

    private var showingFolders = false
    private var overlayGroup = ""

    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val hideOverlay = Runnable { closeOverlay() }
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Store.classic(this)) setTheme(R.style.PlayerTheme_Classic)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.playerView)
        info = findViewById(R.id.info)
        webBox = findViewById(R.id.webBox)
        YtPool.onFailed = failCallback
        YtPool.onEnded = endCallback
        overlay = findViewById(R.id.overlay)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlayHint = findViewById(R.id.overlayHint)
        overlayList = findViewById(R.id.overlayList)
        overlayList.layoutManager = LinearLayoutManager(this)
        overlayList.adapter = overlayAdapter

        group = intent.getStringExtra("group") ?: ""
        chans = Store.inGroup(group)
        if (chans.isEmpty()) { finish(); return }
        index = intent.getIntExtra("index", 0).coerceIn(0, chans.size - 1)

        player = ExoPlayer.Builder(this).build()
        view.player = player
        view.useController = false
        view.keepScreenOn = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = nextStream()
        })

        // Phone: swipe up/down = channel, tap = channel list, long-press = favorite
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (overlay.visibility == View.VISIBLE) closeOverlay() else openOverlay()
                return true
            }
            override fun onLongPress(e: MotionEvent) = toggleFav(chans[index])
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) > abs(vx)) { change(if (vy < 0) 1 else -1); return true }
                return false
            }
        })
        view.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

        play()
    }

    // ------------------------------------------------------------------ playback
    private fun play() {
        streamIdx = 0
        forceHls = false
        start()
        showInfo()
    }

    private var ytLookup = 0

    private fun start() {
        ytLookup++                                   // cancels a YouTube lookup still running
        val ch = chans[index]
        if (!Store.youTubeOn) while (ch.streams.getOrNull(streamIdx)?.isYouTube == true) streamIdx++   // YouTube OFF: skip
        val s = ch.streams.getOrNull(streamIdx)
        if (s == null) {
            stopWeb()
            player.stop()
            showInfo(Lang.t("❌ 無法播放\n按 上/下 換台", "❌ Can't play this channel\nPress up/down to change"), 8000)
            return
        }
        if (s.isYouTube) { openYouTube(s); return }
        stopWeb()
        val ds = DefaultHttpDataSource.Factory()
            .setUserAgent(s.userAgent ?: Net.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        s.referrer?.let { ds.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val u = s.url.lowercase()
        val item = MediaItem.Builder().setUri(s.url)
        when {
            forceHls || u.contains(".m3u") -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            u.contains(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        val source = DefaultMediaSourceFactory(this).setDataSourceFactory(ds).createMediaSource(item.build())
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    /** Official YouTube live, played full screen inside the app (no YouTube app needed). */
    private var extOpened = false

    /** Looks up the channel's current live (not the one saved at the daily check, which may have ended). */
    private fun openYouTube(s: Stream) {
        player.stop()
        val token = ytLookup
        YtPool.fresh[s.url]?.takeIf { System.currentTimeMillis() - it.second < 10 * 60_000 }?.let {
            openYouTubeId(s, it.first); return
        }
        showInfo(Lang.t("🔎 找最新直播…", "🔎 Finding the current live…"), 3000)
        Thread {
            val id = Net.youTubeLive(s.url)
            runOnUiThread {
                if (token != ytLookup || isFinishing) return@runOnUiThread
                if (id != null) YtPool.fresh[s.url] = id to System.currentTimeMillis()
                openYouTubeId(s, id ?: s.extra)
            }
        }.start()
    }

    private fun openYouTubeId(s: Stream, liveId: String?) {
        val ext = Store.youTubeApp(this)
        if (ext != null && liveId != null) {
            try {
                extOpened = true
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/watch?v=$liveId")).setPackage(ext)
                    .putExtra("force_fullscreen", true)        // open straight in full screen (no chat panel)
                    .putExtra("finish_on_ended", true))        // return to STSA TV when it ends
                return
            } catch (e: Exception) { extOpened = false }      // app missing: fall back to built-in
        }
        val id = liveId ?: run { nextStream(); return }
        view.visibility = View.GONE
        webBox.visibility = View.VISIBLE
        webPool.forEach { (k, w) -> if (k != id) { w.evaluateJavascript("try{p.pauseVideo()}catch(e){}", null); w.visibility = View.GONE } }
        val existing = webPool[id]
        if (existing != null) {
            // same player as before: resume and jump to live, no new start (no repeat ads)
            if (existing.parent !== webBox) {
                (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                (existing.context as? android.content.MutableContextWrapper)?.baseContext = this
                existing.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }
                webBox.addView(existing, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            }
            existing.visibility = View.VISIBLE
            existing.evaluateJavascript("try{p.seekTo(p.getDuration(),true);p.playVideo()}catch(e){}", null)
        } else {
            webPool[id] = newYouTubeView(id)
            while (webPool.size > YtPool.MAX) {
                val oldest = webPool.keys.first()
                webPool.remove(oldest)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
            }
        }
        webCurrent = id
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun newYouTubeView(id: String): WebView {
        val w = WebView(android.content.MutableContextWrapper(this))
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.webChromeClient = WebChromeClient()
        w.setBackgroundColor(android.graphics.Color.BLACK)
        w.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun failed(code: Int) { YtPool.onFailed?.invoke(id) }
            @android.webkit.JavascriptInterface
            fun ended() { YtPool.onEnded?.invoke(id) }
        }, "STSA")
        w.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }
        // YouTube's own player, full screen, no controls/chat; if it refuses to play, try the next link
        val html = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body,#p{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
            <body><div id="p"></div><script src="https://www.youtube.com/iframe_api"></script><script>
            var p;function onYouTubeIframeAPIReady(){p=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
            playerVars:{autoplay:1,controls:0,rel:0,modestbranding:1,iv_load_policy:3,playsinline:1,disablekb:1,fs:0,cc_load_policy:0},
            events:{onReady:function(e){e.target.playVideo();},onError:function(e){STSA.failed(e.data);},
            onStateChange:function(e){if(e.data===0)STSA.ended();}}});}
            </script></body></html>"""
        w.loadDataWithBaseURL("https://simpletechsa.github.io/", html, "text/html", "UTF-8", null)
        webBox.addView(w, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        return w
    }

    /** The live ended (show over): find the channel's new live and play it. */
    private val endCallback: (String) -> Unit = { id ->
        runOnUiThread {
            if (webCurrent == id) {
                YtPool.fresh.keys.filter { YtPool.fresh[it]?.first == id }.forEach { YtPool.fresh.remove(it) }
                webPool.remove(id)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
                webCurrent = null
                start()
            }
        }
    }

    private val failCallback: (String) -> Unit = { id ->
        runOnUiThread { if (webCurrent == id && webBox.visibility == View.VISIBLE) nextStream() }
    }

    private fun pauseAllWeb() {
        webPool.values.forEach { it.evaluateJavascript("try{p.pauseVideo()}catch(e){}", null) }
    }

    private fun stopWeb() {
        extOpened = false
        if (webBox.visibility == View.VISIBLE) {
            pauseAllWeb()                      // keep them (paused) for a quick, ad-free return
            webBox.visibility = View.GONE
            view.visibility = View.VISIBLE
            webCurrent = null
        }
    }

    /** Current link failed: try it as HLS once, then the next link. */
    private fun nextStream() {
        val cur = chans[index].streams.getOrNull(streamIdx)
        val u = cur?.url?.lowercase() ?: ""
        if (cur?.isYouTube != true && !forceHls && !u.contains(".m3u") && !u.contains(".mpd")) {
            forceHls = true
        } else {
            forceHls = false
            streamIdx++
            if (streamIdx < chans[index].streams.size) showInfo(Lang.t("🔁 換備用連結 ", "🔁 Trying backup link ") + (streamIdx + 1))
        }
        start()
    }

    private fun change(d: Int) {
        index = (index + d + chans.size) % chans.size
        play()
    }

    private fun playFrom(g: String, i: Int) {
        val list = Store.inGroup(g)
        if (list.isEmpty()) return
        group = g
        chans = list
        index = i.coerceIn(0, chans.size - 1)
        closeOverlay()
        play()
    }

    // ------------------------------------------------------------------ info box
    private fun showInfo(extra: String? = null, ms: Long = 5000) {
        val ch = chans[index]
        val now = System.currentTimeMillis()
        info.text = buildString {
            if (Store.showNumbers(this@PlayerActivity)) append("${index + 1}. ")
            append("${Lang.pick(ch.name)}${ch.label}\n")
            append(Lang.pick(group))
            Store.guide(ch, 2).forEachIndexed { i, pr ->
                val mark = if (i == 0 && pr.start <= now) "▶" else "  "
                append("\n$mark ${hm.format(Date(pr.start))}  ${pr.title}")
            }
            if (extra != null) append("\n\n").append(extra)
        }
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, ms)
    }

    private fun toggleFav(ch: Channel) {
        if (ch.group == Groups.ADULT) return
        val on = Store.toggleFav(this, ch)
        Toast.makeText(this, if (on) Lang.t("已加入最愛", "Added to favorites") else Lang.t("已移除最愛", "Removed from favorites"),
            Toast.LENGTH_SHORT).show()
        if (overlay.visibility == View.VISIBLE) {
            if (showingFolders) showFolders()
            else {
                val pos = focusedOverlayPos()
                showChannelsInOverlay(overlayGroup, focusIndex = pos.coerceAtLeast(0))
            }
        } else {
            showInfo()
        }
    }

    // ------------------------------------------------------------------ see-through channel list
    private fun openOverlay() {
        info.visibility = View.GONE
        overlay.visibility = View.VISIBLE
        showChannelsInOverlay(group, focusIndex = index)
        bumpOverlay()
    }

    private fun closeOverlay() {
        handler.removeCallbacks(hideOverlay)
        overlay.visibility = View.GONE
    }

    /** Hide the list after 12 seconds without a key press. */
    private fun bumpOverlay() {
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 12_000)
    }

    private fun showChannelsInOverlay(g: String, focusIndex: Int = 0) {
        showingFolders = false
        overlayGroup = g
        overlayTitle.text = Lang.pick(g)
        overlayHint.text = Lang.t("OK 播放 · ←/→ 翻頁 · 返回 = 資料夾 · 長按OK = 最愛", "OK play · ←/→ page · Back = folders · hold OK = favorite")
        val list = Store.inGroup(g)
        val now = System.currentTimeMillis()
        overlayAdapter.set(list.mapIndexed { i, ch ->
            val prog = Store.guide(ch, 1).firstOrNull()?.takeIf { it.start <= now }
                ?.let { "▶ ${hm.format(Date(it.start))} ${it.title}" } ?: ytNow(ch).ifEmpty { ch.label.trim() }
            val playing = g == group && i == index
            OverlayAdapter.Item(
                "${if (playing) "🔊 " else ""}${if (Store.showNumbers(this)) "${i + 1}. " else ""}${Lang.pick(ch.name)}",
                prog, Store.logoFor(ch), fav = g != Groups.FAV && Store.isFav(ch),
                onClick = { playFrom(g, i) },
                onLong = if (ch.group == Groups.ADULT) null else ({ toggleFav(ch) }),
            )
        })
        focusOverlay(focusIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
    }

    private fun showFolders() {
        showingFolders = true
        overlayTitle.text = Lang.t("📂 資料夾", "📂 Folders")
        overlayHint.text = Lang.t("OK 打開 · ←/→ 翻頁 · 返回 = 關閉", "OK open · ←/→ page · Back = close")
        val groups = Store.groups().filter { it.first != Groups.ADULT }
        overlayAdapter.set(groups.map { (g, n) ->
            OverlayAdapter.Item(Lang.pick(g), "$n", null, onClick = { showChannelsInOverlay(g) })
        })
        focusOverlay(groups.indexOfFirst { it.first == overlayGroup }.coerceAtLeast(0))
    }

    private fun focusOverlay(pos: Int) {
        overlayList.scrollToPosition(pos)
        overlayList.post {
            val v = overlayList.findViewHolderForAdapterPosition(pos)?.itemView
            if (v != null) v.requestFocus()
            else overlayList.post { overlayList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    private fun focusedOverlayPos(): Int {
        val f = overlayList.focusedChild ?: return -1
        return overlayList.getChildAdapterPosition(f)
    }

    // ------------------------------------------------------------------ remote control
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (overlay.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) bumpOverlay()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { Paging.jump(overlayList, false); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> { Paging.jump(overlayList, true); return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (showingFolders) closeOverlay() else showFolders()   // back = up one folder level
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)   // ↑/↓ move, OK chooses
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { change(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { change(1); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showInfo(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> { toggleFav(chans[index]); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { event.startTracking(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) { toggleFav(chans[index]); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.visibility != View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            if (!event.isCanceled) openOverlay()      // short press = channel list
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onStart() {
        super.onStart()
        if (!::player.isInitialized) return
        if (chans[index].streams.getOrNull(streamIdx)?.isYouTube == true) {
            if (extOpened) { showInfo(); return }               // back from the chosen YouTube app
            webCurrent?.let { webPool[it]?.evaluateJavascript("try{p.seekTo(p.getDuration(),true);p.playVideo()}catch(e){}", null) }
            return
        }
        if (player.playbackState == Player.STATE_IDLE) start() else player.play()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
        if (::webBox.isInitialized) pauseAllWeb()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideInfo)
        handler.removeCallbacks(hideOverlay)
        if (::player.isInitialized) player.release()
        // keep watched YouTube channels for this app session (no repeat ads); just detach them
        pauseAllWeb()
        if (YtPool.onFailed === failCallback) YtPool.onFailed = null
        if (YtPool.onEnded === endCallback) YtPool.onEnded = null
        webBox.removeAllViews()
        webPool.values.forEach { (it.context as? android.content.MutableContextWrapper)?.baseContext = applicationContext }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ list adapter
    class OverlayAdapter : RecyclerView.Adapter<OverlayAdapter.VH>() {
        class Item(
            val title: String, val sub: String, val logo: String?,
            val fav: Boolean = false,
            val onClick: () -> Unit, val onLong: (() -> Unit)? = null,
        )

        private var items: List<Item> = emptyList()

        fun set(list: List<Item>) {
            items = list
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.oLogo)
            val title: TextView = v.findViewById(R.id.oTitle)
            val sub: TextView = v.findViewById(R.id.oSub)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.overlay_row, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.title.text = item.title
            h.sub.text = item.sub
            h.sub.visibility = if (item.sub.isEmpty()) View.GONE else View.VISIBLE
            val logo = item.logo
            if (logo == null) {
                h.logo.visibility = View.GONE
            } else {
                h.logo.visibility = View.VISIBLE
                h.logo.setBackgroundResource(if (item.fav) R.drawable.logo_fav else R.drawable.logo_bg)
                if (logo.isEmpty()) {
                    Glide.with(h.logo).clear(h.logo); h.logo.setImageDrawable(null)
                } else {
                    Glide.with(h.logo).load(logo).into(h.logo)
                }
            }
            h.itemView.setOnClickListener { item.onClick() }
            val long = item.onLong
            if (long != null) h.itemView.setOnLongClickListener { long(); true }
            else { h.itemView.setOnLongClickListener(null); h.itemView.isLongClickable = false }
        }
    }
}
