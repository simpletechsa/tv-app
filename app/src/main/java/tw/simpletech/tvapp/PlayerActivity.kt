package tw.simpletech.tvapp

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var info: TextView
    private lateinit var chans: List<Channel>
    private var index = 0
    private var streamIdx = 0
    private var forceHls = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.visibility = View.GONE }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.playerView)
        info = findViewById(R.id.info)

        chans = Store.inGroup(intent.getStringExtra("group") ?: "")
        if (chans.isEmpty()) { finish(); return }
        index = intent.getIntExtra("index", 0).coerceIn(0, chans.size - 1)

        player = ExoPlayer.Builder(this).build()
        view.player = player
        view.useController = false
        view.keepScreenOn = true
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = nextStream()
        })

        // Phone: swipe up/down = change channel, tap = show name
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { showInfo(guide = true); return true }
            override fun onLongPress(e: MotionEvent) = toggleFav()
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) > abs(vx)) { change(if (vy < 0) 1 else -1); return true }
                return false
            }
        })
        view.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

        play()
    }

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
            player.stop()
            showInfo("❌ 無法播放 Can't play this channel\n按 上/下 換台 Press up/down to change", 8000)
            return
        }
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

    /** Current link failed: try it as HLS once, then the next link. */
    private fun nextStream() {
        val u = chans[index].streams.getOrNull(streamIdx)?.url?.lowercase() ?: ""
        if (!forceHls && !u.contains(".m3u") && !u.contains(".mpd")) {
            forceHls = true
        } else {
            forceHls = false
            streamIdx++
            if (streamIdx < chans[index].streams.size) showInfo("🔁 換備用連結 Trying backup link ${streamIdx + 1}")
        }
        start()
    }

    private fun change(d: Int) {
        index = (index + d + chans.size) % chans.size
        play()
    }

    private fun showInfo(extra: String? = null, ms: Long = 5000, guide: Boolean = false) {
        val ch = chans[index]
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()
        info.text = buildString {
            if (Store.isFav(ch)) append("♥ ")
            append("${index + 1}. ${ch.name}${ch.label}\n")
            append(ch.group)
            val progs = Store.guide(ch, if (guide) 8 else 2)
            if (progs.isNotEmpty()) append("\n")
            progs.forEachIndexed { i, pr ->
                val mark = if (i == 0 && pr.start <= now) "▶ 現在 Now" else "   "
                append("\n$mark  ${hm.format(Date(pr.start))}  ${pr.title}")
            }
            if (!guide) append("\n\n→ 節目表 Guide   長按OK 加入最愛 Hold OK = ♥")
            if (extra != null) append("\n\n").append(extra)
        }
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, if (guide) 10000 else ms)
    }

    private fun toggleFav() {
        val on = Store.toggleFav(this, chans[index])
        Toast.makeText(this, if (on) "♥ 已加入最愛 Added to favorites" else "已移除最愛 Removed from favorites",
            Toast.LENGTH_SHORT).show()
        showInfo()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { change(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { change(1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> { showInfo(guide = true); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> { toggleFav(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { event.startTracking(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { toggleFav(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!event.isCanceled) showInfo()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && player.playbackState == Player.STATE_IDLE) start()
        else if (::player.isInitialized) player.play()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideInfo)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
