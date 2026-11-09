package app.scrctl.link

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Menu Bagikan dari app mana pun: teks -> clipboard PC, file -> folder Downloads\SCRCTL di PC. */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = LinkHub.client
        if (client == null || LinkHub.ui.value.conn != Conn.Connected) {
            toast("SCRCTL Link belum terhubung ke PC")
            finish()
            return
        }

        val uris = extractUris(intent)
        if (uris.isNotEmpty()) {
            toast("Mengirim ${uris.size} file ke PC…")
            // Activity dibiarkan hidup sampai selesai supaya izin baca URI dari app asal tidak dicabut.
            client.sendUris(uris) { runOnUiThread { finish() } }
            return
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrEmpty()) {
            client.sendClipboardText(text)
            toast("Teks dikirim ke clipboard PC")
        } else {
            toast("Tidak ada yang bisa dikirim")
        }
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun extractUris(i: Intent): List<Uri> {
        return when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    i.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                listOfNotNull(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                l?.toList() ?: emptyList()
            }
            else -> emptyList()
        }
    }
}
