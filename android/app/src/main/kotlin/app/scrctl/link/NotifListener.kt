package app.scrctl.link

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Meneruskan notifikasi HP ke PC. Aktif setelah user memberi "Akses notifikasi" di pengaturan Android. */
class NotifListener : NotificationListenerService() {

    private val seen = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val client = LinkHub.client ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (sbn.isOngoing) return // pemutar musik, progres, dsb

        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        // Android sering mengirim ulang notifikasi yang sama (update progres, dsb): kirim sekali per isi
        val sig = "$title\u0001$text"
        if (seen[sbn.key] == sig) return
        if (seen.size > 300) seen.clear()
        seen[sbn.key] = sig

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        client.post(
            JSONObject().put("t", "notif")
                .put("pkg", sbn.packageName)
                .put("app", label)
                .put("title", title.take(300))
                .put("text", text.take(2000))
                .put("ts", sbn.postTime)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key)
    }
}
