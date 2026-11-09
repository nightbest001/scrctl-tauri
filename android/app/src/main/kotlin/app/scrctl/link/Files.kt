package app.scrctl.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/** Menerima file dari PC. Android 10+: masuk ke Download/SCRCTL lewat MediaStore (tanpa izin storage). */
class FileReceiver(private val ctx: Context, private val log: (String, Boolean) -> Unit) {

    private class Incoming(
        val name: String,
        val size: Long,
        val out: OutputStream,
        val uri: Uri?,
        val file: File?,
    ) {
        var got = 0L
    }

    private val map = HashMap<Int, Incoming>()

    @Synchronized
    fun start(id: Int, rawName: String, size: Long) {
        val name = safeName(rawName)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime(name))
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SCRCTL")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IOException("MediaStore menolak")
                val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("tidak bisa menulis")
                map[id] = Incoming(name, size, os, uri, null)
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SCRCTL")
                dir.mkdirs()
                val f = unique(dir, name)
                map[id] = Incoming(name, size, FileOutputStream(f), null, f)
            }
            log("Menerima $name…", false)
        } catch (e: Exception) {
            log("Gagal menyimpan $name: ${e.message}", true)
        }
    }

    @Synchronized
    fun chunk(b: ByteArray) {
        if (b.size < 4) return
        val id = ByteBuffer.wrap(b).getInt(0)
        val f = map[id] ?: return
        try {
            f.out.write(b, 4, b.size - 4)
            f.got += (b.size - 4).toLong()
        } catch (e: IOException) {
            fail(id, f, e)
        }
    }

    @Synchronized
    fun end(id: Int) {
        val f = map.remove(id) ?: return
        try {
            f.out.close()
            if (f.uri != null && Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                ctx.contentResolver.update(f.uri, cv, null, null)
            }
            if (f.size > 0 && f.got != f.size) {
                log("File ${f.name} tidak lengkap (${f.got} dari ${f.size} byte)", true)
            } else {
                log("File diterima: ${f.name} (Download/SCRCTL)", false)
            }
        } catch (e: Exception) {
            log("Gagal menyelesaikan ${f.name}: ${e.message}", true)
        }
    }

    @Synchronized
    fun abortAll() {
        for ((_, f) in map) discard(f)
        map.clear()
    }

    private fun fail(id: Int, f: Incoming, e: Exception) {
        map.remove(id)
        discard(f)
        log("Gagal menulis ${f.name}: ${e.message}", true)
    }

    private fun discard(f: Incoming) {
        try { f.out.close() } catch (_: Exception) {}
        try { f.uri?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
        try { f.file?.delete() } catch (_: Exception) {}
    }

    private fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (f.exists() && n < 1000) {
            f = File(dir, "$stem ($n)$ext")
            n++
        }
        return f
    }

    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.map { if (it in "<>:\"|?*" || it.isISOControl()) '_' else it }.joinToString("")
            .trim().trim('.')
        return cleaned.take(120).ifEmpty { "file" }
    }
}
