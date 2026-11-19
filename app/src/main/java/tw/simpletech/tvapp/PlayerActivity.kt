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
    private lateinit var web: WebView
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
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.playerView)
        info = findViewById(R.id.info)
        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
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
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
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
        web.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

        play()
    }

    // ------------------------------------------------------------------ playback
    private fun play() {
        streamIdx = 0
        forceHls = false
        start()
        showInfo()
    }

    private fun start() {
        val ch = chans[index]
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
    private fun openYouTube(s: Stream) {
        player.stop()
        view.visibility = View.GONE
        web.visibility = View.VISIBLE
        val id = s.extra ?: run { nextStream(); return }
        val html = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>
            </head><body><iframe src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&controls=0&rel=0&modestbranding=1&iv_load_policy=3"
            allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe></body></html>"""
        web.loadDataWithBaseURL("https://simpletechsa.github.io/", html, "text/html", "UTF-8", null)
    }

    private var webPaused = false

    private fun stopWeb() {
        if (web.visibility == View.VISIBLE) {
            web.loadUrl("about:blank")
            web.visibility = View.GONE
            view.visibility = View.VISIBLE
        }
    }

    /** Current link failed: try it as HLS once, then the next link. */
    private fun nextStream() {
        val u = chans[index].streams.getOrNull(streamIdx)?.url?.lowercase() ?: ""
        if (!forceHls && !u.contains(".m3u") && !u.contains(".mpd")) {
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
            if (Store.isFav(ch)) append("♥ ")
            append("${index + 1}. ${Lang.pick(ch.name)}${ch.label}\n")
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
        Toast.makeText(this, if (on) Lang.t("♥ 已加入最愛", "♥ Added to favorites") else Lang.t("已移除最愛", "Removed from favorites"),
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
        overlayHint.text = Lang.t("OK 播放 · ←/→ 翻頁 · 返回 = 資料夾 · 長按OK = ♥", "OK play · ←/→ page · Back = folders · hold OK = ♥")
        val list = Store.inGroup(g)
        val now = System.currentTimeMillis()
        overlayAdapter.set(list.mapIndexed { i, ch ->
            val prog = Store.guide(ch, 1).firstOrNull()?.takeIf { it.start <= now }
                ?.let { "▶ ${hm.format(Date(it.start))} ${it.title}" } ?: ch.label.trim()
            val playing = g == group && i == index
            OverlayAdapter.Item(
                "${if (playing) "🔊 " else ""}${if (Store.isFav(ch)) "♥ " else ""}${i + 1}. ${Lang.pick(ch.name)}",
                prog, Store.logoFor(ch),
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
            if (webPaused) { webPaused = false; start() }      // coming back: reload the YouTube live
            return
        }
        if (player.playbackState == Player.STATE_IDLE) start() else player.play()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
        if (::web.isInitialized && web.visibility == View.VISIBLE) { web.loadUrl("about:blank"); webPaused = true }
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideInfo)
        handler.removeCallbacks(hideOverlay)
        if (::player.isInitialized) player.release()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ list adapter
    class OverlayAdapter : RecyclerView.Adapter<OverlayAdapter.VH>() {
        class Item(
            val title: String, val sub: String, val logo: String?,
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
