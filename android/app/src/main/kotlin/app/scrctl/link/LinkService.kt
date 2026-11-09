package app.scrctl.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Menjaga koneksi ke PC tetap hidup di background (foreground service) dan mendorong baterai. */
class LinkService : Service() {

    companion object {
        const val ACTION_STOP = "app.scrctl.link.STOP"
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: LinkClient? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastBattery: Pair<Int, Boolean>? = null
    private var receiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pushBattery(intent, false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            LinkHub.setConn(Conn.Stopped, null, null)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !Prefs.enabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startFg(buildNotification("Menghubungkan…"))
        if (client == null) setup()
        client?.start(Prefs.host(this), Prefs.port(this), Prefs.token(this))
        return START_STICKY
    }

    private fun setup() {
        val c = LinkClient(this, scope)
        client = c
        LinkHub.client = c
        c.onConnected = {
            pushBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)), true)
            c.commands.pushStatus()
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        receiverRegistered = true

        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "scrctl:link").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            LinkHub.ui.collect { st ->
                nm.notify(NOTIF_ID, buildNotification(st.message.ifEmpty { "Orbit Link" }))
            }
        }
    }

    private fun pushBattery(i: Intent?, force: Boolean) {
        if (i == null) return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val cur = pct to charging
        if (!force && cur == lastBattery) return
        lastBattery = cur
        client?.post(JSONObject().put("t", "battery").put("level", pct).put("charging", charging))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Koneksi ke PC", NotificationManager.IMPORTANCE_LOW))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Putuskan", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Orbit Link")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            // Tipe camera supaya kamera tetap jalan saat layar mati / app di background.
            if (Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            try {
                startForeground(NOTIF_ID, n, type)
            } catch (e: Exception) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        client?.stop(false)
        client = null
        LinkHub.client = null
        LinkHub.setConn(Conn.Stopped, null, null)
        if (receiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        try { wifiLock?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
