package app.scrctl.link

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Cek update lewat GitHub Releases (sama seperti app Windows).
object Updater {
    private const val REPO = "nightbest001/scrctl-tauri"

    data class Info(val tag: String, val apkUrl: String, val pageUrl: String)

    fun currentVersion(c: Context): String =
        try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    private fun parts(v: String): List<Int> =
        v.trim().trimStart('v', 'V').split('.', '-').map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // Panggil dari thread background. Null = tidak ada update / offline.
    fun check(c: Context): Info? {
        return try {
            val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 8000
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("User-Agent", "Orbit-Link")
            if (con.responseCode != 200) return null
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name")
            if (tag.isEmpty() || !isNewer(tag, currentVersion(c))) return null
            val assets = j.optJSONArray("assets") ?: return null
            var apk = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) { apk = a.optString("browser_download_url"); break }
            }
            Info(tag, apk, j.optString("html_url"))
        } catch (e: Exception) { null }
    }
}
