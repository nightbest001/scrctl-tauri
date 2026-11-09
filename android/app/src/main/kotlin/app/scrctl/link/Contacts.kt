package app.scrctl.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Cari nama kontak dari nomor (kosong kalau izin kontak belum ada / nomor tidak tersimpan). */
object Contacts {
    private val cache = HashMap<String, String>()

    fun has(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun name(ctx: Context, number: String): String {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return ""
        cache[number]?.let { return it }
        var res = ""
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) res = it.getString(0) ?: ""
            }
        } catch (_: Exception) {}
        if (cache.size > 500) cache.clear()
        cache[number] = res
        return res
    }
}
