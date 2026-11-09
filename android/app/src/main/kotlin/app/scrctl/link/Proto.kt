package app.scrctl.link

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protokol SCRCTL Link v1 (sama dengan src-tauri/src/link.rs).
 * Frame = u32 BE panjang | u8 jenis | isi. Jenis 0 = JSON, 1 = potongan file (4 byte id BE + data).
 */
object Proto {
    const val MAX_FRAME = 1 shl 20
    const val CHUNK = 128 * 1024

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** HMAC-SHA256(kode, nonce) dalam hex kecil. Kode tidak pernah dikirim. */
    fun proof(token: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(nonce.toByteArray(Charsets.UTF_8)))
    }

    fun normalizeToken(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }
}

class Frame(val kind: Int, val body: ByteArray)

class AuthException : Exception("kode ditolak PC")
