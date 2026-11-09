package app.scrctl.link

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/** Perute fitur tambahan (musik, SMS, telepon, foto, apps). Dipanggil dari LinkClient. */
class Features(
    ctx: Context,
    scope: CoroutineScope,
    private val post: (JSONObject) -> Unit,
    sendUri: (Uri) -> Unit,
    sendFrame: (Int, ByteArray) -> Unit,
) {
    private val ctx = ctx.applicationContext
    val media = PhoneMedia(this.ctx, post)
    val sms = PhoneSms(this.ctx, scope, post)
    val calls = PhoneCalls(this.ctx, scope, post)
    val photos = PhonePhotos(this.ctx, scope, post, sendUri)
    val apps = PhoneApps(this.ctx, scope, post)
    val camera = PhoneCamera(this.ctx, post, sendFrame)

    fun onConnected() {
        media.start()
        media.resend()
        sms.start()
        calls.start()
        pushCaps()
    }

    fun onDisconnected() {
        media.stop()
        sms.stop()
        calls.stop()
        camera.stop()
    }

    /** true = pesan ditangani di sini. */
    fun handle(o: JSONObject): Boolean {
        when (o.optString("t")) {
            "mcmd" -> media.command(o)
            "sms" -> sms.handle(o)
            "calls" -> calls.handle(o)
            "photos" -> photos.handle(o)
            "apps" -> apps.handle(o)
            "cam" -> camera.handle(o)
            "caps" -> { sms.start(); calls.start(); pushCaps() }
            else -> return false
        }
        return true
    }

    private fun pushCaps() {
        post(
            JSONObject().put("t", "caps")
                .put("sms", sms.allowed())
                .put("smsSend", sms.canSend())
                .put("callLog", calls.logAllowed())
                .put("call", calls.canCall())
                .put("photos", photos.allowed())
                .put("contacts", Contacts.has(ctx, Manifest.permission.READ_CONTACTS))
                .put("camera", camera.allowed())
                .put("sdk", Build.VERSION.SDK_INT)
        )
    }
}
