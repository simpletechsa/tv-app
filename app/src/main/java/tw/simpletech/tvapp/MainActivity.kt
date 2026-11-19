package tw.simpletech.tvapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private val adapter = RowAdapter()
    private var group: String? = null      // null = showing groups
    private var groupPos = 0
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.title).setOnClickListener { settings() }   // settings only from the title

        Store.load(this)
        Updater.schedule(this)
        showGroups()
        when {
            Store.channels.isEmpty() -> refresh()                                  // first install: show progress
            Store.channels.any { Groups.order(it.group) == 99 || it.key.startsWith("yt:") } -> refresh()       // old app data: rebuild once
            else -> { showDone(); quietUpdate() }                                  // normal: open now, check silently
        }
    }

    private fun quietUpdate() {
        if (!Updater.checkDue(this) && !Updater.guideDue(this)) return
        thread { Updater.quiet(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        Updater.onUpdated = { runOnUiThread { redraw() } }
        // favorites may have changed while watching
        redraw()
    }

    override fun onPause() {
        Updater.onUpdated = null
        super.onPause()
    }

    // ---------------------------------------------------------------- screens
    private fun showGroups(moveFocus: Boolean = true) {
        group = null
        val rows = Store.groups().map { (g, n) -> Row(g, "$n", null) { pos, _ -> groupPos = pos; showChannels(g) } }
        adapter.set(rows)
        if (moveFocus) focus(groupPos.coerceAtMost((rows.size - 1).coerceAtLeast(0)))
    }

    private fun showChannels(g: String, keepPos: Boolean = false) {
        group = g
        val chans = Store.inGroup(g)
        if (chans.isEmpty()) { showGroups(moveFocus = !keepPos); return }
        val now = SimpleDateFormat("HH:mm", Locale.getDefault())
        val rows = chans.mapIndexed { i, ch ->
            val heart = if (Store.isFav(ch)) "♥ " else ""
            val prog = Store.guide(ch, 1).firstOrNull()
                ?.takeIf { it.start <= System.currentTimeMillis() }
                ?.let { "▶ ${now.format(Date(it.start))} ${it.title}" } ?: ""
            Row("$heart${i + 1}. ${ch.name}${ch.label}", prog, ch.logo,
                onLong = { pos -> toggleFav(ch, pos) }) { _, _ ->
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("group", g).putExtra("index", i))
            }
        }
        val pos = if (keepPos) (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() else 0
        adapter.set(rows)
        if (!keepPos) focus(0) else list.scrollToPosition(pos.coerceAtLeast(0))
    }

    private fun toggleFav(ch: Channel, pos: Int) {
        val on = Store.toggleFav(this, ch)
        Toast.makeText(this, if (on) "♥ 已加入最愛 Added to favorites" else "已移除最愛 Removed from favorites",
            Toast.LENGTH_SHORT).show()
        showChannels(group ?: return, keepPos = true)
        focus(pos.coerceAtMost(adapter.itemCount - 1))
    }

    private fun focus(pos: Int) {
        if (pos < 0) return
        list.scrollToPosition(pos)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val inList = list.hasFocus()
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_PAGE_UP ->
                if (inList) { Paging.jump(list, false); return true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_PAGE_DOWN ->
                if (inList) { Paging.jump(list, true); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (group != null) showGroups() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- check & fix
    private fun setStatus(s: String) = runOnUiThread {
        status.text = s
        status.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showDone() = runOnUiThread { status.visibility = View.GONE }

    private fun settings() {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fun date(t: Long) = if (t == 0L) "—" else fmt.format(Date(t))
        val chans = Store.channels
        val guideCount = chans.count { Store.epg.containsKey(it.key) }
        val msg = buildString {
            append("版本 Version: v$ver\n")
            append("頻道 Channels: ${chans.size}\n")
            append("上次檢查 Last check: ${date(Store.lastCheck(this@MainActivity))}\n")
            append("節目表 TV guide: ${date(Store.lastEpg(this@MainActivity))} ($guideCount channels)\n")
            if (Updater.isRunning) append("\n🔄 背景更新中 Updating in the background…\n")
            if (Updater.vpnOn(this@MainActivity)) append("\n🔒 VPN 開啟中：只更新橄欖球 VPN on: only rugby is updated\n")
            append("\n每天自動在背景檢查 Checked daily in the background.\n")
            append("\n遙控器 Remote: ↑/↓ 換台 channel · OK 頻道清單 list (←/→ 翻頁 page, 返回 資料夾 Back = folders) · 長按OK ♥ hold OK = favorite · 音量用音量鍵 volume keys\n")
            append("手機 Phone: 上下滑 換台 swipe = channel · 點一下 清單 tap = list · 長按 ♥ long-press = favorite")
        }
        AlertDialog.Builder(this)
            .setTitle("⚙ STSA TV 設定 Settings")
            .setMessage(msg)
            .setPositiveButton("🔄 立即檢查 Check now") { _, _ -> refresh() }
            .setNeutralButton("📌 我的清單 My playlists") { _, _ -> editPlaylists() }
            .setNegativeButton("關閉 Close", null)
            .show()
    }

    /** Updates the list without moving the selection. */
    private fun redraw() {
        if (group == null) showGroups(moveFocus = false) else showChannels(group!!, keepPos = true)
    }

    /** Visible check (first install or the Check & fix button). */
    private fun refresh(force: Boolean = false) {
        if (busy) return
        if (!force && Updater.vpnOn(this)) {
            val d = AlertDialog.Builder(this)
                .setTitle("VPN 已開啟 VPN is on")
                .setMessage("要用 VPN 檢查嗎？只會更新橄欖球頻道，台灣頻道不受影響。\n\n" +
                    "Check with the VPN? Only rugby is updated; Taiwan channels stay as they are.")
                .setPositiveButton("是 Yes") { _, _ -> refresh(force = true) }
                .setNegativeButton("否 No", null)
                .show()
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()   // Yes is the default
            return
        }
        busy = true
        setStatus("開始… Starting…")
        thread {
            try {
                Updater.full(applicationContext) { setStatus(it) }
                busy = false
                runOnUiThread { redraw(); showDone() }
            } catch (e: Exception) {
                setStatus("❌ 失敗 Failed: ${e.message ?: e.javaClass.simpleName} — 請檢查網路 Check internet")
                busy = false
            }
        }
    }

    private fun editPlaylists() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_URI
            minLines = 3
            setText(Store.customUrls(this@MainActivity).joinToString("\n"))
            hint = "https://…/playlist.m3u"
        }
        AlertDialog.Builder(this)
            .setTitle("我的播放清單 My playlists")
            .setMessage("每行一個 M3U 連結 One M3U link per line")
            .setView(input)
            .setPositiveButton("儲存並更新 Save & update") { _, _ ->
                Store.setCustomUrls(this, input.text.toString()); refresh()
            }
            .setNegativeButton("取消 Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- list adapter
    class Row(
        val title: String, val sub: String, val logo: String?,
        val onLong: ((Int) -> Unit)? = null, val onClick: (Int, View) -> Unit,
    )

    class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private var rows: List<Row> = emptyList()

        fun set(r: List<Row>) {
            rows = r
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.rowLogo)
            val title: TextView = v.findViewById(R.id.rowTitle)
            val sub: TextView = v.findViewById(R.id.rowSub)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_row, p, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = rows[pos]
            h.title.text = r.title
            h.sub.text = r.sub
            if (r.logo == null) {
                h.logo.visibility = View.GONE
            } else {
                h.logo.visibility = View.VISIBLE
                if (r.logo.isEmpty()) {
                    Glide.with(h.logo).clear(h.logo)
                    h.logo.setImageResource(R.mipmap.ic_launcher)
                } else {
                    Glide.with(h.logo).load(r.logo).placeholder(R.mipmap.ic_launcher)
                        .error(R.mipmap.ic_launcher).into(h.logo)
                }
            }
            h.itemView.setOnClickListener { r.onClick(h.bindingAdapterPosition, it) }
            val long = r.onLong
            if (long != null) {
                h.itemView.setOnLongClickListener { long(h.bindingAdapterPosition); true }
            } else {
                h.itemView.setOnLongClickListener(null)
                h.itemView.isLongClickable = false
            }
        }
    }
}
