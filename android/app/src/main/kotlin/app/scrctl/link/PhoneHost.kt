package app.scrctl.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** Status mode "dikontrol" untuk layar HostActivity dan notifikasi. */
data class HostUi(val running: Boolean = false, val client: String? = null, val screen: Boolean = false, val error: String = "")

object HostHub {
    val ui = MutableStateFlow(HostUi())
}

object HostCode {
    // tanpa huruf/angka yang mirip (I, O, 0, 1)
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(): String {
        val r = SecureRandom()
        return buildString { repeat(12) { append(ALPHA[r.nextInt(ALPHA.length)]) } }
    }

    fun pretty(t: String): String = t.chunked(4).joinToString("-")

    /** Alamat IPv4 jaringan lokal HP ini (WiFi atau hotspot), lengkap dengan port. */
    fun addresses(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        out.add("${a.hostAddress}:${PhoneHost.PORT}")
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }
}

/**
 * Server Orbit Link di HP ini: HP lain yang memasukkan kode bisa mengontrol HP ini.
 * Handshake dan enkripsi sama dengan Orbit di PC (protokol v2), hanya peran server.
 */
class PhoneHost(private val ctx: Context, private val token: String, private val screen: ScreenShare) {

    companion object {
        const val PORT = 5589
        private const val MAX_FAILS = 5
        private const val LOCK_MS = 30_000L
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var current: Session? = null
    @Volatile private var stopped = false
    private val preAuth = AtomicInteger(0)
    private val failLock = Any()
    private var fails = 0
    private var lockUntil = 0L
    private val inject = Injector(ctx)

    private inner class Session(val sock: Socket) {
        val din = DataInputStream(BufferedInputStream(sock.getInputStream()))
        val dout = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
        @Volatile var wc: Crypt? = null
        var rc: Crypt? = null
        private val wlock = Any()

        fun close() { try { sock.close() } catch (_: Exception) {} }

        fun send(kind: Int, body: ByteArray) {
            synchronized(wlock) {
                val payload = ByteArray(1 + body.size)
                payload[0] = kind.toByte()
                System.arraycopy(body, 0, payload, 1, body.size)
                val c = wc
                val data = if (c != null) c.seal(payload) else payload
                dout.writeInt(data.size)
                dout.write(data)
                dout.flush()
            }
        }

        fun sendJson(o: JSONObject) = send(0, o.toString().toByteArray(Charsets.UTF_8))

        fun read(): Frame {
            val len = din.readInt()
            val c = rc
            val max = if (c != null) Proto.MAX_SEALED else Proto.MAX_FRAME
            if (len < 1 || len > max) throw IOException("frame tidak valid")
            val buf = ByteArray(len)
            din.readFully(buf)
            val plain = if (c != null) c.open(buf) else buf
            if (plain.isEmpty()) throw IOException("frame kosong")
            return Frame(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
        }

        fun readJson(): JSONObject {
            val f = read()
            if (f.kind != 0) throw IOException("bukan JSON")
            return JSONObject(String(f.body, Charsets.UTF_8))
        }
    }

    fun start() {
        stopped = false
        Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(PORT))
                server = ss
                HostHub.ui.value = HostUi(running = true)
                while (!stopped) {
                    val s = ss.accept()
                    if (preAuth.get() >= 4) { try { s.close() } catch (_: Exception) {}; continue }
                    Thread({ serve(s) }, "orbit-host-conn").apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (!stopped) HostHub.ui.value = HostUi(running = false, error = "Server gagal jalan: ${e.message ?: e.javaClass.simpleName}")
            }
        }, "orbit-host").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
        server = null
        current?.close()
        current = null
        screen.stop()
        inject.reset()
        HostHub.ui.value = HostUi()
    }

    private fun locked(): Boolean = synchronized(failLock) { SystemClock.elapsedRealtime() < lockUntil }

    private fun noteFail() = synchronized(failLock) {
        fails++
        if (fails >= MAX_FAILS) {
            fails = 0
            lockUntil = SystemClock.elapsedRealtime() + LOCK_MS
        }
    }

    private fun clearFails() = synchronized(failLock) { fails = 0 }

    private fun serve(s: Socket) {
        var sess: Session? = null
        var authed = false
        preAuth.incrementAndGet()
        try {
            s.tcpNoDelay = true
            s.soTimeout = 20_000
            val me = Session(s)
            sess = me
            if (locked()) {
                me.sendJson(JSONObject().put("t", "deny").put("reason", "locked"))
                return
            }
            val sNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            me.sendJson(
                JSONObject().put("t", "challenge").put("ver", Proto.VERSION)
                    .put("nonce", Proto.hex(sNonce)).put("pc", Build.MODEL).put("kind", "phone")
            )
            val hello = me.readJson()
            if (hello.optString("t") != "hello") return
            if (hello.optInt("ver", 1) < Proto.VERSION) {
                me.sendJson(JSONObject().put("t", "deny").put("reason", "version"))
                return
            }
            val cNonce = try { Proto.unhex(hello.optString("cnonce")) } catch (_: IOException) { ByteArray(0) }
            if (cNonce.size != 16) return
            val keys = Proto.deriveKeys(token, sNonce, cNonce)
            val got = try { Proto.unhex(hello.optString("proof")) } catch (_: IOException) { ByteArray(0) }
            if (!Proto.sameBytes(Proto.proof(keys, "C", sNonce, cNonce), got)) {
                noteFail()
                me.sendJson(JSONObject().put("t", "deny"))
                return
            }
            clearFails()
            me.sendJson(
                JSONObject().put("t", "welcome").put("pc", Build.MODEL)
                    .put("proof", Proto.hex(Proto.proof(keys, "S", sNonce, cNonce)))
            )
            me.rc = Crypt(keys.c2s)
            me.wc = Crypt(keys.s2c)

            // HP pengontrol baru menggantikan yang lama
            current?.close()
            current = me
            authed = true
            preAuth.decrementAndGet()
            s.soTimeout = 30_000
            val name = hello.optString("name", "HP lain").take(40)
            HostHub.ui.value = HostUi(running = true, client = name)

            while (true) {
                val f = me.read()
                if (f.kind == 0) handle(me, JSONObject(String(f.body, Charsets.UTF_8)))
            }
        } catch (_: Exception) {
        } finally {
            if (!authed) preAuth.decrementAndGet()
            val me = sess
            me?.close()
            if (me != null && current === me) {
                current = null
                screen.stop()
                inject.reset()
                HostHub.ui.value = HostUi(running = !stopped)
            }
        }
    }

    private fun pushState(sess: Session) {
        try {
            sess.sendJson(
                JSONObject().put("t", "pc-state").put("control", true).put("kind", "phone")
                    .put("screen", screen.active).put("w", screen.width).put("h", screen.height)
                    .put("input", RemoteInputService.instance != null)
            )
        } catch (_: Exception) {}
    }

    private fun handle(sess: Session, o: JSONObject) {
        when (o.optString("t")) {
            "pc" -> when (o.optString("a")) {
                "state" -> pushState(sess)
                "abs" -> inject.abs(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat())
                "down" -> inject.down()
                "up" -> inject.up()
                "click" -> inject.click(o.optString("b", "left"), o.optInt("n", 1).coerceIn(1, 3))
                "scroll" -> inject.scroll(o.optInt("dy"))
                "text" -> inject.text(o.optString("s"))
                "key" -> inject.key(o.optString("k"))
                "nav" -> inject.nav(o.optString("k"))
            }
            "screen" -> when (o.optString("a")) {
                "start" -> {
                    val ok = screen.start(o.optInt("max", 960).coerceIn(320, 1920), o.optInt("fps", 10), o.optInt("q", 50)) { jpeg ->
                        try { sess.send(3, jpeg) } catch (_: Exception) {}
                    }
                    HostHub.ui.value = HostHub.ui.value.copy(screen = ok)
                    pushState(sess)
                }
                "stop" -> {
                    screen.stop()
                    HostHub.ui.value = HostHub.ui.value.copy(screen = false)
                    pushState(sess)
                }
            }
        }
    }
}

/** Mengubah pesan input dari HP pengontrol menjadi gerakan dan tombol di HP ini. */
class Injector(private val ctx: Context) {
    private var cx = 0.5f
    private var cy = 0.5f
    private var dragging = false
    private val path = ArrayList<FloatArray>()
    private var t0 = 0L
    private val main = Handler(Looper.getMainLooper())
    private var scrollAcc = 0f
    private var scrollPending = false

    private fun svc() = RemoteInputService.instance

    private fun px(): Pair<Int, Int> {
        val r = realScreenSize(ctx)
        return r[0] to r[1]
    }

    private fun pt(nx: Float, ny: Float): FloatArray {
        val (w, h) = px()
        return floatArrayOf((nx * w).coerceIn(0f, w - 1f), (ny * h).coerceIn(0f, h - 1f))
    }

    fun reset() {
        dragging = false
        path.clear()
        scrollAcc = 0f
    }

    fun abs(x: Float, y: Float) {
        cx = x.coerceIn(0f, 1f)
        cy = y.coerceIn(0f, 1f)
        if (dragging && path.size < 600) path.add(pt(cx, cy))
    }

    fun down() {
        dragging = true
        path.clear()
        path.add(pt(cx, cy))
        t0 = SystemClock.uptimeMillis()
    }

    /** Seret/tahan dijalankan saat jari dilepas, dengan durasi yang sama seperti di HP pengontrol. */
    fun up() {
        if (!dragging) return
        dragging = false
        path.add(pt(cx, cy))
        val dur = (SystemClock.uptimeMillis() - t0).coerceIn(60L, 20_000L)
        val g = oneStroke(strokePath(ArrayList(path)), 0L, dur)
        path.clear()
        main.post { svc()?.gesture(g) }
    }

    fun click(b: String, n: Int) {
        when (b) {
            "right" -> svc()?.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "middle" -> svc()?.global(AccessibilityService.GLOBAL_ACTION_HOME)
            else -> {
                val p = pt(cx, cy)
                val gb = GestureDescription.Builder()
                for (i in 0 until n) {
                    gb.addStroke(GestureDescription.StrokeDescription(strokePath(listOf(p)), i * 140L, 40L))
                }
                val g = gb.build()
                main.post { svc()?.gesture(g) }
            }
        }
    }

    /** dy positif = gulir ke atas (isi bergerak turun, jari menggeser ke bawah). Digabung tiap 90 ms. */
    fun scroll(dy: Int) {
        if (dy == 0) return
        scrollAcc += dy
        if (scrollPending) return
        scrollPending = true
        main.postDelayed({
            scrollPending = false
            val (w, h) = px()
            val dist = (scrollAcc / 120f * 0.12f * h).coerceIn(-0.6f * h, 0.6f * h)
            scrollAcc = 0f
            if (Math.abs(dist) < 8f) return@postDelayed
            val x = (cx * w).coerceIn(1f, w - 2f)
            val y0 = h / 2f - dist / 2f
            val y1 = h / 2f + dist / 2f
            svc()?.gesture(oneStroke(strokePath(listOf(floatArrayOf(x, y0), floatArrayOf(x, y1))), 0L, 160L))
        }, 90)
    }

    fun text(s: String) {
        if (s.isNotEmpty()) svc()?.insertText(s)
    }

    fun key(k: String) {
        val s = svc()
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (k) {
            "enter" -> s?.imeEnter()
            "backspace" -> s?.backspace()
            "delete" -> s?.deleteForward()
            "left" -> s?.moveCursor(-1)
            "right" -> s?.moveCursor(1)
            "esc" -> s?.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "win" -> s?.global(AccessibilityService.GLOBAL_ACTION_HOME)
            "vol_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "vol_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            "play_pause" -> media(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> media(audio, KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> media(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "stop" -> media(audio, KeyEvent.KEYCODE_MEDIA_STOP)
        }
    }

    private fun media(audio: AudioManager, code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun nav(k: String) {
        val s = svc() ?: return
        when (k) {
            "back" -> s.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> s.global(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> s.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notif" -> s.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick" -> s.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) s.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
