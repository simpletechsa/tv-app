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
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        findViewById<View>(R.id.btnCheck).setOnClickListener { refresh() }
        findViewById<View>(R.id.btnAdd).setOnClickListener { editPlaylists() }

        Store.load(this)
        showGroups()
        val age = System.currentTimeMillis() - Store.lastCheck(this)
        if (Store.channels.isEmpty() || age > TimeUnit.DAYS.toMillis(14)) refresh() else showDone()
    }

    // ---------------------------------------------------------------- screens
    private fun showGroups() {
        group = null
        val rows = Store.groups().map { (g, n) -> Row(g, "$n") { pos -> groupPos = pos; showChannels(g) } }
        adapter.set(rows)
        focus(groupPos)
    }

    private fun showChannels(g: String) {
        group = g
        val chans = Store.inGroup(g)
        val rows = chans.mapIndexed { i, ch ->
            Row("${i + 1}.  ${ch.name}${ch.label}", "") {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("group", g).putExtra("index", i)
                )
            }
        }
        adapter.set(rows)
        focus(0)
    }

    private fun focus(pos: Int) {
        list.scrollToPosition(pos)
        list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (group != null) showGroups() else super.onBackPressed()
    }

    // ---------------------------------------------------------------- check & fix
    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun showDone() {
        val d = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(Store.lastCheck(this)))
        val ok = Store.channels.count { it.status == "ok" }
        setStatus("✅ ${Store.channels.size} 個頻道 channels (正常 working: $ok) · 上次檢查 Last check: $d")
    }

    private fun refresh() {
        if (busy) return
        busy = true
        setStatus("開始… Starting…")
        thread {
            try {
                val prev = Store.channels.associateBy { it.key }
                val fresh = Builder.build(Store.customUrls(this)) { setStatus(it) }
                Checker.run(fresh, prev) { done, total ->
                    if (done % 10 == 0 || done == total)
                        setStatus("🔄 檢查連結中 Checking links: $done / $total （可繼續看電視 you can keep watching）")
                }
                val good = fresh.filter { it.status != "dead" }
                Store.save(this, good)
                runOnUiThread {
                    busy = false
                    if (group == null) showGroups() else showChannels(group!!)
                    showDone()
                }
            } catch (e: Exception) {
                busy = false
                setStatus("❌ 失敗 Failed: ${e.message ?: e.javaClass.simpleName} — 請檢查網路 Check internet")
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
    class Row(val title: String, val sub: String, val onClick: (Int) -> Unit)

    class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private var rows: List<Row> = emptyList()

        fun set(r: List<Row>) {
            rows = r
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
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
            h.itemView.setOnClickListener { r.onClick(h.bindingAdapterPosition) }
        }
    }
}
