package app.scrctl.link

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Daftar app yang bisa dibuka (dengan ikon kecil) untuk halaman Apps di PC. */
class PhoneApps(private val ctx: Context, private val scope: CoroutineScope, private val post: (JSONObject) -> Unit) {

    fun handle(o: JSONObject) {
        if (o.optString("a") != "list") return
        scope.launch(Dispatchers.IO) {
            try { list() } catch (e: Exception) {
                post(JSONObject().put("t", "event").put("err", true).put("text", "Daftar app gagal: ${e.message}"))
            }
        }
    }

    private fun list() {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
        var batch = JSONArray()
        var first = true
        for (a in apps) {
            val icon = try {
                val d = pm.getApplicationIcon(a.first)
                val bmp = d.toBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } catch (_: Exception) { "" }
            batch.put(JSONObject().put("pkg", a.first).put("label", a.second).put("icon", icon))
            if (batch.length() >= 20) {
                post(JSONObject().put("t", "apps").put("list", batch).put("first", first).put("last", false))
                batch = JSONArray(); first = false
            }
        }
        post(JSONObject().put("t", "apps").put("list", batch).put("first", first).put("last", true))
    }
}
