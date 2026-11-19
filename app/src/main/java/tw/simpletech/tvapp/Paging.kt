package tw.simpletech.tvapp

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** ←/→ = jump one screen of rows up/down in a list. */
object Paging {
    fun jump(list: RecyclerView, forward: Boolean): Boolean {
        val lm = list.layoutManager as? LinearLayoutManager ?: return false
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) return false
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        val page = (last - first).coerceAtLeast(1)
        val cur = list.focusedChild?.let { list.getChildAdapterPosition(it) }?.takeIf { it >= 0 } ?: first
        val target = (if (forward) cur + page else cur - page).coerceIn(0, count - 1)
        if (target == cur) return true
        lm.scrollToPositionWithOffset(target, 0)
        list.post {
            list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                ?: list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        }
        return true
    }
}
