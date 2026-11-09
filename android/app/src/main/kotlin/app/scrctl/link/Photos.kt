package app.scrctl.link

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Foto terbaru di HP (thumbnail) dan kirim file aslinya ke PC lewat jalur kirim file yang sudah ada. */
class PhonePhotos(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val post: (JSONObject) -> Unit,
    private val sendUri: (Uri) -> Unit,
) {
    private val base: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun allowed(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) Contacts.has(ctx, Manifest.permission.READ_MEDIA_IMAGES)
        else Contacts.has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun handle(o: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                when (o.optString("a")) {
                    "list" -> list(o.optInt("limit", 60).coerceIn(1, 200))
                    "get" -> sendUri(ContentUris.withAppendedId(base, o.optLong("id")))
                }
            } catch (e: Exception) {
                post(JSONObject().put("t", "event").put("err", true).put("text", "Foto gagal: ${e.message}"))
            }
        }
    }

    private fun list(limit: Int) {
        if (!allowed()) {
            post(JSONObject().put("t", "event").put("err", true)
                .put("text", "Izin foto belum diberikan. Buka app Orbit Link di HP, bagian Izin."))
            post(JSONObject().put("t", "photos").put("list", JSONArray()).put("first", true).put("last", true))
            return
        }
        val ids = ArrayList<Triple<Long, String, Long>>()
        ctx.contentResolver.query(
            base,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED),
            null, null, MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { c ->
            while (c.moveToNext() && ids.size < limit) ids.add(Triple(c.getLong(0), c.getString(1) ?: "foto", c.getLong(2) * 1000))
        }
        var batch = JSONArray()
        var first = true
        for ((i, p) in ids.withIndex()) {
            val thumb = thumb(p.first) ?: continue
            batch.put(JSONObject().put("id", p.first).put("name", p.second).put("date", p.third).put("thumb", thumb))
            if (batch.length() >= 8) {
                post(JSONObject().put("t", "photos").put("list", batch).put("first", first).put("last", false))
                batch = JSONArray(); first = false
            }
            if (i == ids.lastIndex) break
        }
        post(JSONObject().put("t", "photos").put("list", batch).put("first", first).put("last", true))
    }

    @Suppress("DEPRECATION")
    private fun thumb(id: Long): String? = try {
        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(ContentUris.withAppendedId(base, id), Size(240, 240), null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
        bmp?.let {
            val out = ByteArrayOutputStream()
            it.compress(Bitmap.CompressFormat.JPEG, 70, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (_: Exception) { null }
}
