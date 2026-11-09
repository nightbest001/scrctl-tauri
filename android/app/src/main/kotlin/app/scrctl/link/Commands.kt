package app.scrctl.link

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.provider.Settings
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Perintah dari PC: senter, volume, Jangan Ganggu, cari HP. Setelah tiap perintah, status dikirim balik. */
class Commands(private val ctx: Context, private val post: (JSONObject) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val cm = ctx.getSystemService(CameraManager::class.java)

    @Volatile private var torchOn = false
    private var ringtone: Ringtone? = null

    private val pushTask = Runnable { pushStatus() }
    private val netReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            main.removeCallbacks(pushTask)
            main.postDelayed(pushTask, 700)
        }
    }

    init {
        // Wi-Fi / Bluetooth / hotspot diubah dari PC lewat adb: status ikut diperbarui otomatis.
        try {
            val f = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(netReceiver, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(netReceiver, f)
        } catch (_: Exception) {}
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchOn = enabled
                }
            }, main)
        } catch (_: Exception) {}
    }

    fun run(o: JSONObject) {
        val on: Boolean? = if (o.isNull("on")) null else o.optBoolean("on")
        try {
            when (o.optString("cmd")) {
                "flashlight" -> setTorch(on ?: !torchOn)
                "vol_up" -> adjust(AudioManager.ADJUST_RAISE)
                "vol_down" -> adjust(AudioManager.ADJUST_LOWER)
                "mute" -> adjust(AudioManager.ADJUST_TOGGLE_MUTE)
                "dnd" -> setDnd(on)
                "find" -> find(on ?: true)
            }
        } catch (e: Exception) {
            err("Perintah gagal: ${e.message}")
        }
        pushStatus()
    }

    fun statusJson(): JSONObject {
        val granted = nm.isNotificationPolicyAccessGranted
        val o = JSONObject().put("t", "status").put("torch", torchOn)
        if (granted) {
            o.put("dnd", nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN })
        }
        wifiOn()?.let { o.put("wifi", it) }
        btOn()?.let { o.put("bt", it) }
        hotspotOn()?.let { o.put("hotspot", it) }
        return o
    }

    private fun wifiOn(): Boolean? = try {
        ctx.getSystemService(WifiManager::class.java).isWifiEnabled
    } catch (_: Exception) { null }

    private fun btOn(): Boolean? = try {
        Settings.Global.getInt(ctx.contentResolver, "bluetooth_on") == 1
    } catch (_: Exception) { null }

    // API tersembunyi: di beberapa versi Android diblokir, hasilnya null (status hotspot tidak diketahui).
    private fun hotspotOn(): Boolean? = try {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val m = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
        m.isAccessible = true
        m.invoke(wm) as? Boolean
    } catch (_: Throwable) { null }

    fun pushStatus() = post(statusJson())

    private fun err(text: String) {
        post(JSONObject().put("t", "event").put("text", text).put("err", true))
    }

    private fun setTorch(on: Boolean) {
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) {
            err("HP ini tidak punya senter")
            return
        }
        cm.setTorchMode(id, on)
        torchOn = on
    }

    private fun adjust(direction: Int) {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun setDnd(on: Boolean?) {
        if (!nm.isNotificationPolicyAccessGranted) {
            err("Izin Jangan Ganggu belum diberikan. Buka app SCRCTL Link di HP, bagian Izin.")
            return
        }
        val active = nm.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        val enable = on ?: !active
        nm.setInterruptionFilter(
            if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    private fun find(on: Boolean) {
        stopRing()
        if (!on) return
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        val r = RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        if (r == null) {
            err("Nada dering tidak tersedia")
            return
        }
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
        r.play()
        ringtone = r
        main.postDelayed({ stopRing() }, 20_000)
    }

    private fun stopRing() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }
}
