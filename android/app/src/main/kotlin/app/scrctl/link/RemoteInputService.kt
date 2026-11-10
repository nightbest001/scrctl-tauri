package app.scrctl.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Menjalankan input dari HP pengontrol di HP ini: ketuk, geser, tombol navigasi, dan mengetik.
 * Hanya aktif jika pemilik HP menyalakannya sendiri di Setelan > Aksesibilitas.
 */
class RemoteInputService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteInputService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun gesture(g: GestureDescription) {
        try { dispatchGesture(g, null, null) } catch (_: Exception) {}
    }

    fun global(action: Int) {
        try { performGlobalAction(action) } catch (_: Exception) {}
    }

    // ---- teks ----
    private fun focusedInput(): AccessibilityNodeInfo? =
        try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }

    private fun setText(n: AccessibilityNodeInfo, text: String, cursor: Int) {
        val a = Bundle()
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)
        val s = Bundle()
        s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
        s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, s)
    }

    /** Teks saat ini, awal dan akhir seleksi (sudah dipastikan valid). */
    private class Edit(val n: AccessibilityNodeInfo, val cur: String, val lo: Int, val hi: Int)

    private fun edit(): Edit? {
        val n = focusedInput() ?: return null
        val cur = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
        var a = n.textSelectionStart
        var b = n.textSelectionEnd
        if (a < 0 || b < 0 || a > cur.length || b > cur.length) { a = cur.length; b = cur.length }
        return Edit(n, cur, minOf(a, b), maxOf(a, b))
    }

    fun insertText(s: String): Boolean {
        val e = edit() ?: return false
        setText(e.n, e.cur.substring(0, e.lo) + s + e.cur.substring(e.hi), e.lo + s.length)
        return true
    }

    fun backspace(): Boolean {
        val e = edit() ?: return false
        if (e.hi > e.lo) {
            setText(e.n, e.cur.substring(0, e.lo) + e.cur.substring(e.hi), e.lo)
        } else if (e.lo > 0) {
            setText(e.n, e.cur.substring(0, e.lo - 1) + e.cur.substring(e.lo), e.lo - 1)
        }
        return true
    }

    fun deleteForward(): Boolean {
        val e = edit() ?: return false
        if (e.hi > e.lo) {
            setText(e.n, e.cur.substring(0, e.lo) + e.cur.substring(e.hi), e.lo)
        } else if (e.lo < e.cur.length) {
            setText(e.n, e.cur.substring(0, e.lo) + e.cur.substring(e.lo + 1), e.lo)
        }
        return true
    }

    fun moveCursor(delta: Int): Boolean {
        val e = edit() ?: return false
        val to = (e.lo + delta).coerceIn(0, e.cur.length)
        val s = Bundle()
        s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, to)
        s.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, to)
        e.n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, s)
        return true
    }

    fun imeEnter(): Boolean {
        val n = focusedInput() ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        }
        return false
    }
}

/** Jalur dari titik 0..1 ke gerakan di layar. [pts] dalam piksel. */
fun strokePath(pts: List<FloatArray>): Path {
    val p = Path()
    p.moveTo(pts[0][0], pts[0][1])
    for (i in 1 until pts.size) p.lineTo(pts[i][0], pts[i][1])
    return p
}

fun oneStroke(p: Path, start: Long, dur: Long): GestureDescription =
    GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(p, start, dur.coerceIn(1L, 20_000L)))
        .build()
