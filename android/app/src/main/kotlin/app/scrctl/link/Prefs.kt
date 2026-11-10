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

    /** Toggle di app HP: boleh tidaknya PC memakai kamera (default boleh). */
    fun allow(c: Context, key: String): Boolean = sp(c).getBoolean("allow_$key", true)
    fun setAllow(c: Context, key: String, on: Boolean) { sp(c).edit().putBoolean("allow_$key", on).apply() }

    /** Pengaturan kontrol PC: kecepatan kursor (1..3), gulir natural, kualitas layar (0..2). */
    fun padSpeed(c: Context): Int = sp(c).getInt("pad_speed", 2)
    fun setPadSpeed(c: Context, v: Int) { sp(c).edit().putInt("pad_speed", v).apply() }
    fun naturalScroll(c: Context): Boolean = sp(c).getBoolean("natural_scroll", true)
    fun setNaturalScroll(c: Context, v: Boolean) { sp(c).edit().putBoolean("natural_scroll", v).apply() }
    fun screenQuality(c: Context): Int = sp(c).getInt("screen_quality", 1)
    fun setScreenQuality(c: Context, v: Int) { sp(c).edit().putInt("screen_quality", v).apply() }

    /** Kode untuk mode "HP ini bisa dikontrol". Dibuat sekali, bisa diganti dari layar mode itu. */
    fun hostToken(c: Context): String {
        val cur = sp(c).getString("host_token", null)
        if (cur != null && cur.length == 12) return cur
        return regenHostToken(c)
    }

    fun regenHostToken(c: Context): String {
        val t = HostCode.generate()
        sp(c).edit().putString("host_token", t).apply()
        return t
    }

    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("enabled", on).apply()
    }
}
