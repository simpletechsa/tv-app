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

        Lang.init(this)
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
        val rows = Store.groups().map { (g, n) ->
            Row(Lang.pick(g), if (g == Groups.ADULT) "" else "$n", null) { pos, _ ->
                groupPos = pos
                if (g == Groups.ADULT) secretPress() else showChannels(g)
            }
        }
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
            Row("$heart${i + 1}. ${Lang.pick(ch.name)}${ch.label}", prog, Store.logoFor(ch),
                onLong = { pos -> toggleFav(ch, pos) }) { _, _ ->
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("group", g).putExtra("index", i))
            }
        }
        val pos = if (keepPos) (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() else 0
        adapter.set(rows)
        if (!keepPos) focus(0) else list.scrollToPosition(pos.coerceAtLeast(0))
    }

    /** Rated folder: OK pressed 5 times within 3 seconds opens it. Leaving it locks it again. */
    private val presses = ArrayDeque<Long>()

    private fun secretPress() {
        val now = System.currentTimeMillis()
        presses.addLast(now)
        while (presses.isNotEmpty() && now - presses.first() > 3000) presses.removeFirst()
        if (presses.size >= 5) { presses.clear(); showChannels(Groups.ADULT) }
    }

    private fun toggleFav(ch: Channel, pos: Int) {
        if (ch.group == Groups.ADULT) return
        val on = Store.toggleFav(this, ch)
        Toast.makeText(this, if (on) Lang.t("♥ 已加入最愛", "♥ Added to favorites") else Lang.t("已移除最愛", "Removed from favorites"),
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
        val t = Lang::t
        val msg = buildString {
            append(t("版本：", "Version: ") + "v$ver\n")
            append(t("頻道：", "Channels: ") + "${chans.size}\n")
            append(t("上次檢查：", "Last check: ") + date(Store.lastCheck(this@MainActivity)) + "\n")
            append(t("節目表：", "TV guide: ") + date(Store.lastEpg(this@MainActivity)) +
                t("（$guideCount 個頻道）", " ($guideCount channels)") + "\n")
            if (Updater.isRunning) append(t("\n🔄 背景更新中…\n", "\n🔄 Updating in the background…\n"))
            if (Updater.vpnOn(this@MainActivity)) append(t("\n🔒 VPN 開啟中：只更新橄欖球\n", "\n🔒 VPN on: only rugby is updated\n"))
            append(t("\n每天自動在背景檢查。\n", "\nChecked daily in the background.\n"))
            append(t("\n遙控器：↑/↓ 換台 · OK 頻道清單（←/→ 翻頁，返回 = 資料夾）· 長按OK = ♥ · 音量用音量鍵\n",
                "\nRemote: ↑/↓ channel · OK list (←/→ page, Back = folders) · hold OK = ♥ · volume keys for volume\n"))
            append(t("手機：上下滑 換台 · 點一下 清單 · 長按 = ♥", "Phone: swipe = channel · tap = list · long-press = ♥"))
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        box.addView(TextView(this).apply { text = msg; textSize = 15f; setPadding(0, 0, 0, pad) })
        val dialog = AlertDialog.Builder(this)
            .setTitle(t("⚙ STSA TV 設定", "⚙ STSA TV Settings"))
            .setView(scroll)
            .setNegativeButton(t("關閉", "Close"), null)
            .create()
        fun button(label: String, action: () -> Unit) = TextView(this).apply {
            text = label; textSize = 17f
            setBackgroundResource(R.drawable.focus_bg)
            setTextColor(getColor(R.color.text))
            isFocusable = true; isClickable = true
            setPadding(pad, pad * 2 / 3, pad, pad * 2 / 3)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad / 2 }
            setOnClickListener { dialog.dismiss(); action() }
        }
        val first = button(t("🔄 立即檢查", "🔄 Check now")) { refresh() }
        box.addView(first)
        box.addView(button(t("📌 我的清單", "📌 My playlists")) { editPlaylists() })
        box.addView(button(t("🌐 語言：中文 → 切換為 English", "🌐 Language: English → switch to 中文")) {
            Lang.set(this, !Lang.zh); recreate()
        })
        box.addView(button(if (Store.adminOn) t("🔐 管理員：開 → 關閉", "🔐 Admin: ON → turn off")
                           else t("🔐 管理員：關 → 開啟", "🔐 Admin: OFF → turn on")) {
            if (Store.adminOn) { Store.setAdmin(this, false); group = null; showGroups() }
            else askPassword { Store.setAdmin(this, true); showGroups() }
        })
        dialog.show()
        first.requestFocus()
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
                .setTitle(Lang.t("VPN 已開啟", "VPN is on"))
                .setMessage(Lang.t("要用 VPN 檢查嗎？只會更新橄欖球頻道，台灣頻道不受影響。",
                    "Check with the VPN? Only rugby is updated; Taiwan channels stay as they are."))
                .setPositiveButton(Lang.t("是", "Yes")) { _, _ -> refresh(force = true) }
                .setNegativeButton(Lang.t("否", "No"), null)
                .show()
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()   // Yes is the default
            return
        }
        busy = true
        setStatus(Lang.t("開始…", "Starting…"))
        thread {
            try {
                Updater.full(applicationContext) { setStatus(it) }
                busy = false
                runOnUiThread { redraw(); showDone() }
            } catch (e: Exception) {
                setStatus(Lang.t("❌ 失敗：", "❌ Failed: ") + (e.message ?: e.javaClass.simpleName) + Lang.t(" — 請檢查網路", " — check internet"))
                busy = false
            }
        }
    }

    private fun passwordBox() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun askPassword(onOk: () -> Unit) {
        val input = passwordBox()
        AlertDialog.Builder(this)
            .setTitle(Lang.t("輸入密碼", "Enter password"))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (Store.checkPassword(input.text.toString())) onOk()
                else Toast.makeText(this, Lang.t("密碼錯誤", "Wrong password"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(Lang.t("取消", "Cancel"), null)
            .show()
        input.requestFocus()
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
            .setTitle(Lang.t("我的播放清單", "My playlists"))
            .setMessage(Lang.t("每行一個 M3U 連結", "One M3U link per line"))
            .setView(input)
            .setPositiveButton(Lang.t("儲存並更新", "Save & update")) { _, _ ->
                Store.setCustomUrls(this, input.text.toString()); refresh()
            }
            .setNegativeButton(Lang.t("取消", "Cancel"), null)
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
