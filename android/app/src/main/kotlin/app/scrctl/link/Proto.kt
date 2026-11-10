package app.scrctl.link

import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protokol Orbit Link v2 (sama dengan src-tauri/src/link.rs; baca komentar di kepala file itu).
 * Frame = u32 BE panjang | isi. Isi = jenis (u8) + data. Setelah handshake isi dienkripsi AES-256-GCM.
 * Jenis 0 = JSON, 1 = potongan file (4 byte id BE + data), 2 = kamera HP, 3 = layar PC.
 */
object Proto {
    const val MAX_FRAME = 1 shl 20
    const val MAX_SEALED = MAX_FRAME + 16
    const val CHUNK = 128 * 1024
    const val KDF_ROUNDS = 100_000
    const val VERSION = 2

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        if (s.length % 2 != 0) throw IOException("hex tidak valid")
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: throw IOException("hex tidak valid")
        }
    }

    class Keys(val auth: ByteArray, val c2s: ByteArray, val s2c: ByteArray)

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** Kunci sesi dari kode dan dua nonce. Sengaja mahal (PBKDF2) supaya menebak kode dari rekaman handshake tidak praktis. */
    fun deriveKeys(token: String, sNonce: ByteArray, cNonce: ByteArray): Keys {
        val salt = "orbit-link-v2".toByteArray(Charsets.UTF_8) + sNonce + cNonce
        val spec = PBEKeySpec(token.toCharArray(), salt, KDF_ROUNDS, 256)
        val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Keys(
            auth = hmac(master, "auth".toByteArray()),
            c2s = hmac(master, "c2s".toByteArray()),
            s2c = hmac(master, "s2c".toByteArray()),
        )
    }

    /** who = "C" untuk bukti HP, "S" untuk bukti PC. */
    fun proof(keys: Keys, who: String, sNonce: ByteArray, cNonce: ByteArray): ByteArray =
        hmac(keys.auth, who.toByteArray(), sNonce, cNonce)

    fun sameBytes(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun normalizeToken(s: String): String = s.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
}

/** Satu arah aliran terenkripsi AES-256-GCM. Nonce = 4 byte nol + counter u64 BE, jadi tidak pernah berulang. */
class Crypt(key: ByteArray) {
    private val k = SecretKeySpec(key, "AES")
    private var n = 0L

    private fun nextNonce(): ByteArray {
        val iv = ByteArray(12)
        var v = n++
        for (i in 11 downTo 4) {
            iv[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return iv
    }

    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nextNonce()))
        return c.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nextNonce()))
        try {
            return c.doFinal(sealed)
        } catch (e: Exception) {
            throw IOException("frame gagal didekripsi")
        }
    }
}

class Frame(val kind: Int, val body: ByteArray)

class AuthException : Exception("kode ditolak PC")
