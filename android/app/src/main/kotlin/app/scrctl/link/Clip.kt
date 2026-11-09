package app.scrctl.link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

object Clip {
    private val main = Handler(Looper.getMainLooper())

    /** Menulis clipboard boleh dari background. (Membacanya tidak: Android 10+ cuma untuk app di layar.) */
    fun setText(ctx: Context, text: String) {
        main.post {
            try {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("SCRCTL", text))
            } catch (e: Exception) {
                LinkHub.log("Gagal menulis clipboard: ${e.message}", true)
            }
        }
    }

    /** Hanya dipanggil dari activity yang sedang tampil. */
    fun getText(ctx: Context): String? {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }
    }
}
