package app.scrctl.link

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Koneksi ke SCRCTL di PC: handshake HMAC, auto-reconnect, kirim/terima pesan dan file. */
class LinkClient(context: Context, private val scope: CoroutineScope) {

    private val ctx = context.applicationContext

    @Volatile private var sock: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    private val writeLock = Mutex()
    private var job: Job? = null
    private val fileIds = AtomicInteger(1)

    private val receiver = FileReceiver(ctx) { text, err -> LinkHub.log(text, err) }
    val commands = Commands(ctx) { post(it) }
    val features = Features(ctx, scope, { post(it) }, { u -> sendUris(listOf(u)) })

    /** Dipanggil (di thread IO) setiap kali handshake berhasil. */
    var onConnected: (() -> Unit)? = null

    fun start(host: String, port: Int, token: String) {
        stop(false)
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive) {
                LinkHub.setConn(Conn.Connecting, null, "Menghubungkan ke $host:$port…")
                try {
                    session(host, port, token) { backoff = 1000L }
                } catch (e: AuthException) {
                    LinkHub.setConn(Conn.AuthFailed, null, "Kode ditolak PC. Cek kode di halaman Link HP.")
                    closeSock()
                    return@launch
                } catch (e: Exception) {
                    if (!isActive) return@launch
                    LinkHub.setConn(Conn.Waiting, null, "Terputus: ${e.message ?: e.javaClass.simpleName}. Mencoba lagi…")
                }
                closeSock()
                if (!isActive) return@launch
                delay(backoff)
                backoff = minOf(backoff * 2, 15_000L)
            }
        }
    }

    fun stop(updateUi: Boolean = true) {
        job?.cancel()
        job = null
        closeSock()
        if (updateUi) LinkHub.setConn(Conn.Stopped, null, null)
    }

    private fun closeSock() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        out = null
    }

    private suspend fun session(host: String, port: Int, token: String, onReady: () -> Unit): Unit = coroutineScope {
        val s = Socket()
        sock = s
        s.connect(InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        s.soTimeout = 10_000
        val din = DataInputStream(BufferedInputStream(s.getInputStream()))
        out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

        val ch = readJson(din)
        if (ch.optString("t") != "challenge") throw IOException("bukan server SCRCTL")
        val pc = ch.optString("pc", "PC")
        sendJson(
            JSONObject().put("t", "hello")
                .put("proof", Proto.proof(token, ch.getString("nonce")))
                .put("name", Build.MODEL)
                .put("ver", 1)
        )
        val resp = readJson(din)
        when (resp.optString("t")) {
            "welcome" -> {}
            "deny" -> throw AuthException()
            else -> throw IOException("handshake gagal")
        }

        s.soTimeout = 30_000
        onReady()
        LinkHub.setConn(Conn.Connected, pc, "Terhubung ke $pc")
        LinkHub.log("Terhubung ke $pc", false)
        features.onConnected()
        onConnected?.invoke()

        launch {
            while (true) {
                delay(10_000)
                sendJson(JSONObject().put("t", "ping"))
            }
        }
        try {
            while (true) {
                val f = readFrame(din)
                if (f.kind == 0) handleJson(JSONObject(String(f.body, Charsets.UTF_8))) else receiver.chunk(f.body)
            }
        } finally {
            features.onDisconnected()
            receiver.abortAll()
        }
    }

    private fun handleJson(o: JSONObject) {
        when (o.optString("t")) {
            "clip" -> {
                Clip.setText(ctx, o.optString("text"))
                LinkHub.log("Clipboard PC → HP tersinkron", false)
            }
            "cmd" -> commands.run(o)
            "file-start" -> receiver.start(o.optInt("id"), o.optString("name"), o.optLong("size"))
            "file-end" -> receiver.end(o.optInt("id"))
            else -> features.handle(o)
        }
    }

    // ---- kirim ----
    fun post(o: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try { sendJson(o) } catch (_: Exception) {}
        }
    }

    private suspend fun sendJson(o: JSONObject) = sendRaw(0, o.toString().toByteArray(Charsets.UTF_8))

    private suspend fun sendRaw(kind: Int, body: ByteArray) {
        val o = out ?: throw IOException("tidak terhubung")
        writeLock.withLock {
            try {
                o.writeInt(body.size + 1)
                o.writeByte(kind)
                o.write(body)
                o.flush()
            } catch (e: IOException) {
                closeSock()
                throw e
            }
        }
    }

    fun sendClipboardText(text: String) {
        post(JSONObject().put("t", "clip").put("text", text))
    }

    /** Kirim banyak URI berurutan di background; [done] dipanggil setelah semua selesai (sukses atau gagal). */
    fun sendUris(uris: List<Uri>, done: () -> Unit = {}) {
        LinkHub.scope.launch {
            try {
                for (u in uris) {
                    try {
                        sendUri(u)
                    } catch (e: Exception) {
                        LinkHub.log("Gagal kirim: ${e.message}", true)
                    }
                }
            } finally {
                done()
            }
        }
    }

    private suspend fun sendUri(uri: Uri) = withContext(Dispatchers.IO) {
        var name = "file"
        var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val input = ctx.contentResolver.openInputStream(uri)
        if (input == null) {
            LinkHub.log("Tidak bisa membuka $name", true)
            return@withContext
        }
        val id = fileIds.getAndIncrement()
        LinkHub.log("Mengirim $name…", false)
        input.use { ins ->
            sendJson(JSONObject().put("t", "file-start").put("id", id).put("name", name).put("size", size))
            val buf = ByteArray(Proto.CHUNK)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                val chunk = ByteArray(4 + n)
                ByteBuffer.wrap(chunk).putInt(id).put(buf, 0, n)
                sendRaw(1, chunk)
            }
            sendJson(JSONObject().put("t", "file-end").put("id", id))
        }
        LinkHub.log("Terkirim ke PC: $name", false)
    }

    // ---- baca ----
    private fun readFrame(d: DataInputStream): Frame {
        val len = d.readInt()
        if (len < 1 || len > Proto.MAX_FRAME) throw IOException("frame tidak valid")
        val kind = d.readUnsignedByte()
        val body = ByteArray(len - 1)
        d.readFully(body)
        return Frame(kind, body)
    }

    private fun readJson(d: DataInputStream): JSONObject {
        val f = readFrame(d)
        if (f.kind != 0) throw IOException("bukan JSON")
        return JSONObject(String(f.body, Charsets.UTF_8))
    }
}
