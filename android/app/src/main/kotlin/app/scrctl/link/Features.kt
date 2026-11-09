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

    private fun featuresOn() = Prefs.allow(ctx, "features")

    /** Toggle di app HP: Pesan/Telepon/Foto boleh dipakai PC atau tidak. */
    fun setFeaturesAllowed(on: Boolean) {
        if (on) { sms.start(); calls.start() } else { sms.stop(); calls.stop() }
        pushCaps()
    }

    fun onConnected() {
        media.start()
        media.resend()
        if (featuresOn()) { sms.start(); calls.start() }
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
            "sms" -> if (featuresOn()) sms.handle(o)
            "calls" -> if (featuresOn()) calls.handle(o)
            "photos" -> if (featuresOn()) photos.handle(o)
            "apps" -> apps.handle(o)
            "cam" -> camera.handle(o)
            "caps" -> { if (featuresOn()) { sms.start(); calls.start() }; pushCaps() }
            else -> return false
        }
        return true
    }

    private fun pushCaps() {
        val f = featuresOn()
        post(
            JSONObject().put("t", "caps")
                .put("sms", f && sms.allowed())
                .put("smsSend", f && sms.canSend())
                .put("callLog", f && calls.logAllowed())
                .put("call", f && calls.canCall())
                .put("photos", f && photos.allowed())
                .put("contacts", f && Contacts.has(ctx, Manifest.permission.READ_CONTACTS))
                .put("camera", camera.allowed())
                .put("sdk", Build.VERSION.SDK_INT)
        )
    }
}
