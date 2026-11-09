package app.scrctl.link

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("link", Context.MODE_PRIVATE)

    fun host(c: Context): String = sp(c).getString("host", "") ?: ""
    fun port(c: Context): Int = sp(c).getInt("port", 5588)
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)

    fun save(c: Context, host: String, port: Int, token: String) {
        sp(c).edit().putString("host", host).putInt("port", port).putString("token", token).apply()
    }

    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("enabled", on).apply()
    }
}
