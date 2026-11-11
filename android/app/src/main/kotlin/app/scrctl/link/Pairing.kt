package app.scrctl.link

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Sambung lewat QR atau tautan: orbit://connect?host=IP:PORT&code=KODE
 * Format sama dengan QR yang dibuat Orbit di PC.
 */
object Pairing {
    class Target(val host: String, val port: Int, val code: String)

    fun build(addr: String, token: String): String = "orbit://connect?host=$addr&code=$token"

    /** Terima tautan orbit://, atau teks biasa "alamat kode" / "alamat:port kode". Null jika tidak valid. */
    fun parse(raw: String): Target? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        var hostPart: String?
        var code: String?
        if (t.startsWith("orbit://", ignoreCase = true)) {
            val u = try { Uri.parse(t) } catch (_: Exception) { return null }
            if (!"connect".equals(u.host, ignoreCase = true)) return null
            hostPart = u.getQueryParameter("host")
            code = u.getQueryParameter("code")
        } else {
            val parts = t.split(Regex("\\s+"))
            if (parts.size != 2) return null
            hostPart = parts[0]
            code = parts[1]
        }
        if (hostPart.isNullOrBlank() || code.isNullOrBlank()) return null
        val h = hostPart.trim()
        val i = h.lastIndexOf(':')
        val host: String
        val port: Int
        if (i < 0) { host = h; port = 5588 }
        else {
            host = h.substring(0, i)
            port = h.substring(i + 1).toIntOrNull() ?: return null
        }
        if (host.isEmpty() || port !in 1..65535) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        val tk = Proto.normalizeToken(code)
        if (tk.length < 8 || tk.length > 16) return null
        return Target(host, port, tk)
    }

    /** Gambar QR hitam di atas putih (kuiet 2 modul). */
    fun qrBitmap(text: String, px: Int = 640): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
        val pixels = IntArray(px * px)
        for (y in 0 until px) for (x in 0 until px) pixels[y * px + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
    }

    /** Buka pemindai QR Google. Tanpa izin kamera di app ini. Perlu Google Play Services. */
    fun scan(activity: Activity, onText: (String) -> Unit, onError: (String) -> Unit) {
        val opts = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(activity, opts)
        scanner.startScan()
            .addOnSuccessListener { b -> b.rawValue?.let(onText) ?: onError("QR kosong") }
            .addOnCanceledListener { }
            .addOnFailureListener { e ->
                onError(
                    "Pemindai QR tidak tersedia (${e.message ?: "perlu Google Play Services"}). " +
                        "Isi alamat dan kode secara manual."
                )
            }
    }
}
