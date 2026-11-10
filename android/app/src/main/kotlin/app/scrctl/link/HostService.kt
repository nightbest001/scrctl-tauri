package app.scrctl.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Menjalankan server "HP ini bisa dikontrol" di background selama mode ini dinyalakan. */
class HostService : Service() {

    companion object {
        const val ACTION_STOP = "app.scrctl.link.HOST_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "host"
        private const val NOTIF_ID = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var host: PhoneHost? = null
    private var share: ScreenShare? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (host != null) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "HP ini dikontrol", NotificationManager.IMPORTANCE_LOW))
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)
        val n = buildNotification("Menunggu HP pengontrol")
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 14 menolak tipe mediaProjection kalau izin tangkap layar tidak diberikan
            val type = if (data != null) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, n, type)
        } else {
            startForeground(NOTIF_ID, n)
        }
        val s = ScreenShare(this)
        try {
            if (data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                s.attach(mpm.getMediaProjection(code, data))
            }
        } catch (_: Exception) {
            // tanpa izin tangkap layar, kontrol sentuh tetap jalan tapi layar tidak tampil
        }
        share = s
        s.onStopped = { HostHub.ui.value = HostHub.ui.value.copy(screen = false) }
        val h = PhoneHost(this, Prefs.hostToken(this), s)
        host = h
        h.start()

        scope.launch {
            HostHub.ui.collect { st ->
                val text = when {
                    st.error.isNotEmpty() -> st.error
                    st.client != null -> "Dikontrol oleh ${st.client}"
                    else -> "Menunggu HP pengontrol"
                }
                nm.notify(NOTIF_ID, buildNotification(text))
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 2, Intent(this, HostActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 3, Intent(this, HostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val act = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Hentikan", stop
        ).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Orbit Link: mode dikontrol")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(act)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        host?.stop()
        host = null
        share?.release()
        share = null
        HostHub.ui.value = HostUi()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
