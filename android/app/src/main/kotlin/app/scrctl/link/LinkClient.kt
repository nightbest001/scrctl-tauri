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

/** Koneksi ke Orbit di PC: handshake saling membuktikan kode, data terenkripsi AES-GCM, auto-reconnect. */
class LinkClient(context: Context, private val scope: CoroutineScope) {

    private val ctx = context.applicationContext

    @Volatile private var sock: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var wc: Crypt? = null // kunci arah HP -> PC; null selama handshake
    private var rc: Crypt? = null // kunci arah PC -> HP
    private val writeLock = Mutex()
    private var job: Job? = null
    private val fileIds = AtomicInteger(1)

    private val receiver = FileReceiver(ctx) { text, err -> LinkHub.log(text, err) }
    val commands = Commands(ctx) { post(it) }
    val features = Features(ctx, scope, { post(it) }, { u -> sendUris(listOf(u)) }, { k, b -> sendFrame(k, b) })

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
        wc = null
        rc = null
    }

    private suspend fun session(host: String, port: Int, token: String, onReady: () -> Unit): Unit = coroutineScope {
        val s = Socket()
        sock = s
        s.connect(InetSocketAddress(host, port), 10_000)
        s.tcpNoDelay = true
        s.soTimeout = 10_000
        val din = DataInputStream(BufferedInputStream(s.getInputStream()))
        out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

        val ch = readJson(din, null)
        if (ch.optString("t") != "challenge") throw IOException("bukan server Orbit")
        if (ch.optInt("ver", 1) < Proto.VERSION) throw IOException("Orbit di PC versi lama. Perbarui Orbit di PC")
        val pc = ch.optString("pc", "PC")
        val sNonce = Proto.unhex(ch.getString("nonce"))
        val cNonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val keys = Proto.deriveKeys(token, sNonce, cNonce)
        sendJson(
            JSONObject().put("t", "hello")
                .put("ver", Proto.VERSION)
                .put("cnonce", Proto.hex(cNonce))
                .put("proof", Proto.hex(Proto.proof(keys, "C", sNonce, cNonce)))
                .put("name", deviceName()),
            plain = true,
        )
        val resp = readJson(din, null)
        when (resp.optString("t")) {
            "welcome" -> {
                // PC juga harus membuktikan tahu kode, supaya server palsu tidak bisa menyamar
                val expect = Proto.proof(keys, "S", sNonce, cNonce)
                val got = try { Proto.unhex(resp.optString("proof")) } catch (_: IOException) { ByteArray(0) }
                if (!Proto.sameBytes(expect, got)) throw IOException("PC tidak bisa membuktikan kode. Sambungan dibatalkan")
            }
            "deny" -> {
                if (resp.optString("reason") == "version") throw IOException("Versi Orbit di PC dan HP berbeda. Perbarui keduanya")
                throw AuthException()
            }
            else -> throw IOException("handshake gagal")
        }
        rc = Crypt(keys.s2c)
        wc = Crypt(keys.c2s)

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
                val f = readFrame(din, rc)
                when (f.kind) {
                    0 -> handleJson(JSONObject(String(f.body, Charsets.UTF_8)))
                    3 -> LinkHub.pc.onFrame(f.body)
                    else -> receiver.chunk(f.body)
                }
            }
        } finally {
            features.onDisconnected()
            LinkHub.pc.onDisconnected()
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
            "pc-state" -> LinkHub.pc.onState(o)
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

    // Antrean berurutan untuk input PC (tekan/lepas tombol harus sampai dengan urutan yang sama).
    private val seqExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "orbit-input").apply { isDaemon = true } }
    private val seqPending = AtomicInteger(0)

    /** Kirim pesan berurutan. Kalau antrean menumpuk (jaringan lambat), pesan gerak dan gulir dibuang. */
    fun postSeq(o: JSONObject) {
        val a = o.optString("a")
        val droppable = a == "move" || a == "abs" || a == "scroll"
        if (droppable && seqPending.get() > 48) return
        seqPending.incrementAndGet()
        seqExec.execute {
            try {
                kotlinx.coroutines.runBlocking { sendJson(o) }
            } catch (_: Exception) {
            } finally {
                seqPending.decrementAndGet()
            }
        }
    }

    private val frameBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Kirim frame biner (mis. kamera). Kalau frame sebelumnya belum selesai terkirim, frame ini dibuang. */
    fun sendFrame(kind: Int, body: ByteArray) {
        if (!frameBusy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try { sendRaw(kind, body) } catch (_: Exception) {} finally { frameBusy.set(false) }
        }
    }

    private suspend fun sendJson(o: JSONObject, plain: Boolean = false) =
        sendRaw(0, o.toString().toByteArray(Charsets.UTF_8), plain)

    /** [plain] hanya untuk pesan handshake. Selain itu pesan baru boleh keluar setelah kunci sesi siap. */
    private suspend fun sendRaw(kind: Int, body: ByteArray, plain: Boolean = false) {
        val o = out ?: throw IOException("tidak terhubung")
        writeLock.withLock {
            try {
                val payload = ByteArray(1 + body.size)
                payload[0] = kind.toByte()
                System.arraycopy(body, 0, payload, 1, body.size)
                val c = wc
                if (c == null && !plain) throw IOException("sambungan belum siap")
                val sealed = if (c != null) c.seal(payload) else payload
                o.writeInt(sealed.size)
                o.write(sealed)
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
    private fun readFrame(d: DataInputStream, crypt: Crypt?): Frame {
        val len = d.readInt()
        val max = if (crypt != null) Proto.MAX_SEALED else Proto.MAX_FRAME
        if (len < 1 || len > max) throw IOException("frame tidak valid")
        val buf = ByteArray(len)
        d.readFully(buf)
        val plain = if (crypt != null) crypt.open(buf) else buf
        if (plain.isEmpty()) throw IOException("frame kosong")
        return Frame(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
    }

    private fun readJson(d: DataInputStream, crypt: Crypt?): JSONObject {
        val f = readFrame(d, crypt)
        if (f.kind != 0) throw IOException("bukan JSON")
        return JSONObject(String(f.body, Charsets.UTF_8))
    }
}

/** "V2109" saja membingungkan; tambahkan merek: "vivo V2109", "Redmi Note 8 Pro". */
internal fun deviceName(): String {
    val brand = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    if (model.isEmpty()) return brand.ifEmpty { "HP" }
    return if (brand.isEmpty() || model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
}
