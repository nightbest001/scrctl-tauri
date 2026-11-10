package app.scrctl.link

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Status kontrol PC yang dilaporkan Orbit di PC. [control] null = belum diketahui. */
data class PcState(
    val control: Boolean? = null,
    val screenOn: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    /** "phone" kalau yang dikontrol HP lain, bukan PC. */
    val kind: String = "pc",
    /** Khusus HP target: layanan Aksesibilitas aktif sehingga sentuhan bisa dijalankan. */
    val input: Boolean = true,
)

/** Frame layar PC terbaru. [seq] naik tiap frame supaya UI tahu harus menggambar ulang. */
class ScreenFrame(val bitmap: Bitmap, val seq: Long)

/**
 * Kontrol PC dari HP: kirim input (mouse, keyboard, media) dan terima gambar layar PC.
 * Pesan input dikirim berurutan lewat [LinkClient.postSeq] supaya "tekan" dan "lepas" tidak tertukar.
 */
class PcRemote {
    val state = MutableStateFlow(PcState())
    val frame = MutableStateFlow<ScreenFrame?>(null)

    private var seq = 0L
    private val decoding = AtomicBoolean(false)

    private fun send(a: String, fill: JSONObject.() -> Unit = {}) {
        val o = JSONObject().put("t", "pc").put("a", a)
        o.fill()
        LinkHub.client?.postSeq(o)
    }

    // ---- mouse ----
    fun move(dx: Int, dy: Int) { if (dx != 0 || dy != 0) send("move") { put("dx", dx); put("dy", dy) } }
    fun abs(x: Float, y: Float) = send("abs") { put("x", x.toDouble()); put("y", y.toDouble()) }
    fun button(b: String, down: Boolean) = send(if (down) "down" else "up") { put("b", b) }
    fun click(b: String = "left", n: Int = 1) = send("click") { put("b", b); put("n", n) }

    /** Satuan 120 = satu klik roda. dy positif = gulir ke atas. */
    fun scroll(dx: Int, dy: Int) { if (dx != 0 || dy != 0) send("scroll") { put("dx", dx); put("dy", dy) } }

    // ---- keyboard dan media ----
    fun text(s: String) { if (s.isNotEmpty()) send("text") { put("s", s) } }
    fun key(k: String, mods: List<String> = emptyList()) = send("key") {
        put("k", k)
        if (mods.isNotEmpty()) put("mods", org.json.JSONArray(mods))
    }

    // ---- layar ----
    fun startScreen(maxWidth: Int, fps: Int, quality: Int) {
        LinkHub.client?.postSeq(
            JSONObject().put("t", "screen").put("a", "start").put("max", maxWidth).put("fps", fps).put("q", quality)
        )
    }

    fun stopScreen() {
        LinkHub.client?.postSeq(JSONObject().put("t", "screen").put("a", "stop"))
        frame.value = null
    }

    /** Tombol sistem HP target: back, home, recents, notif, quick, lock. */
    fun nav(k: String) = send("nav") { put("k", k) }

    fun requestState() = send("state")

    // ---- dari PC ----
    fun onState(o: JSONObject) {
        state.update {
            PcState(
                control = o.optBoolean("control", false),
                screenOn = o.optBoolean("screen", false),
                width = o.optInt("w"),
                height = o.optInt("h"),
                kind = o.optString("kind", "pc"),
                input = o.optBoolean("input", true),
            )
        }
        if (!state.value.screenOn) frame.value = null
    }

    /** Frame JPEG dari PC. Kalau decoder masih sibuk, frame ini dibuang supaya tidak menumpuk. */
    fun onFrame(jpeg: ByteArray) {
        if (!decoding.compareAndSet(false, true)) return
        LinkHub.scope.launch(Dispatchers.Default) {
            try {
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp != null) frame.value = ScreenFrame(bmp, ++seq)
            } catch (_: Throwable) {
            } finally {
                decoding.set(false)
            }
        }
    }

    fun onDisconnected() {
        state.value = PcState()
        frame.value = null
    }
}
