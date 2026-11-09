package app.scrctl.link

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Telepon: riwayat panggilan, telepon dari PC, jawab/tolak, dan status panggilan masuk. */
class PhoneCalls(private val ctx: Context, private val scope: CoroutineScope, private val post: (JSONObject) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val telecom = ctx.getSystemService(TelecomManager::class.java)
    private var listener: PhoneStateListener? = null
    private var lastState = -1
    private var lastNumber = ""

    fun logAllowed() = Contacts.has(ctx, Manifest.permission.READ_CALL_LOG)
    fun canCall() = Contacts.has(ctx, Manifest.permission.CALL_PHONE)
    fun canAnswer() = Contacts.has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)

    @Suppress("DEPRECATION")
    fun start() {
        if (listener != null || !Contacts.has(ctx, Manifest.permission.READ_PHONE_STATE)) return
        main.post {
            try {
                val l = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        val num = phoneNumber.orEmpty().ifEmpty { if (state == lastState) lastNumber else "" }
                        if (state == lastState && num == lastNumber) return
                        lastState = state
                        if (num.isNotEmpty()) lastNumber = num
                        val s = when (state) {
                            TelephonyManager.CALL_STATE_RINGING -> "ringing"
                            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                            else -> "idle"
                        }
                        scope.launch(Dispatchers.IO) {
                            post(JSONObject().put("t", "call-state").put("state", s)
                                .put("number", num).put("name", Contacts.name(ctx, num)))
                        }
                        if (state == TelephonyManager.CALL_STATE_IDLE) lastNumber = ""
                    }
                }
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                listener = l
            } catch (_: Exception) {}
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        val l = listener ?: return
        listener = null
        main.post { try { tm.listen(l, PhoneStateListener.LISTEN_NONE) } catch (_: Exception) {} }
    }

    fun handle(o: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                when (o.optString("a")) {
                    "log" -> log()
                    "dial" -> dial(o.optString("number"))
                    "answer" -> if (canAnswer()) telecom.acceptRingingCall() else err("Izin menjawab panggilan belum diberikan")
                    "end" -> if (!canAnswer()) err("Izin mengakhiri panggilan belum diberikan")
                        else if (Build.VERSION.SDK_INT >= 28) telecom.endCall()
                        else err("Tolak/akhiri panggilan dari PC butuh Android 9+")
                }
            } catch (e: Exception) {
                err("Telepon gagal: ${e.message}")
            }
        }
    }

    private fun err(text: String) = post(JSONObject().put("t", "event").put("err", true).put("text", text))

    private fun dial(raw: String) {
        val number = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (number.isEmpty()) return
        if (!canCall()) { err("Izin telepon belum diberikan. Buka app SCRCTL Link di HP, bagian Izin."); return }
        telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
    }

    private fun log() {
        if (!logAllowed()) { err("Izin riwayat panggilan belum diberikan. Buka app SCRCTL Link di HP, bagian Izin."); return }
        val arr = JSONArray()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            while (c.moveToNext() && arr.length() < 60) {
                val num = c.getString(0) ?: ""
                val kind = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "in"
                    CallLog.Calls.OUTGOING_TYPE -> "out"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "other"
                }
                arr.put(JSONObject().put("number", num)
                    .put("name", c.getString(1).orEmpty().ifEmpty { Contacts.name(ctx, num) })
                    .put("kind", kind).put("date", c.getLong(3)).put("dur", c.getLong(4)))
            }
        }
        post(JSONObject().put("t", "call-log").put("list", arr).put("canCall", canCall()))
    }
}
