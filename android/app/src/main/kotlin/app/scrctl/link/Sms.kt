package app.scrctl.link

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** SMS HP -> PC: daftar percakapan, isi percakapan, kirim SMS. (MMS belum didukung.) */
class PhoneSms(private val ctx: Context, private val scope: CoroutineScope, private val post: (JSONObject) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private val notify = Runnable { post(JSONObject().put("t", "sms-changed")) }

    fun allowed() = Contacts.has(ctx, Manifest.permission.READ_SMS)
    fun canSend() = Contacts.has(ctx, Manifest.permission.SEND_SMS)

    fun start() {
        if (observer != null || !allowed()) return
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(notify)
                main.postDelayed(notify, 600)
            }
        }.also { ctx.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, it) }
    }

    fun stop() {
        observer?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        observer = null
        main.removeCallbacks(notify)
    }

    fun handle(o: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                when (o.optString("a")) {
                    "threads" -> threads()
                    "thread" -> thread(o.optLong("tid"))
                    "send" -> send(o.optString("to"), o.optString("text"))
                }
            } catch (e: Exception) {
                post(JSONObject().put("t", "event").put("err", true).put("text", "SMS gagal: ${e.message}"))
            }
        }
    }

    private fun threads() {
        if (!allowed()) { denied(); return }
        val seen = LinkedHashMap<Long, JSONObject>()
        val unread = HashMap<Long, Int>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id", "address", "body", "date", "type", "read"),
            null, null, "date DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < 1500) {
                n++
                val tid = c.getLong(0)
                if (c.getInt(4) == 1 && c.getInt(5) == 0) unread[tid] = (unread[tid] ?: 0) + 1
                if (seen.containsKey(tid) || seen.size >= 60) continue
                val addr = c.getString(1) ?: ""
                seen[tid] = JSONObject().put("tid", tid).put("address", addr)
                    .put("name", Contacts.name(ctx, addr))
                    .put("snippet", (c.getString(2) ?: "").take(120))
                    .put("date", c.getLong(3)).put("out", c.getInt(4) == 2)
            }
        }
        val arr = JSONArray()
        for ((tid, j) in seen) arr.put(j.put("unread", unread[tid] ?: 0))
        post(JSONObject().put("t", "sms-threads").put("list", arr).put("canSend", canSend()))
    }

    private fun thread(tid: Long) {
        if (!allowed()) { denied(); return }
        val rows = ArrayList<JSONObject>()
        var addr = ""
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "type"),
            "thread_id = ?", arrayOf(tid.toString()), "date DESC",
        )?.use { c ->
            while (c.moveToNext() && rows.size < 80) {
                if (addr.isEmpty()) addr = c.getString(1) ?: ""
                rows.add(JSONObject().put("id", c.getLong(0)).put("body", c.getString(2) ?: "")
                    .put("date", c.getLong(3)).put("out", c.getInt(4) != 1))
            }
        }
        rows.reverse()
        post(JSONObject().put("t", "sms-thread").put("tid", tid).put("address", addr)
            .put("name", Contacts.name(ctx, addr)).put("msgs", JSONArray(rows)))
    }

    private fun send(to: String, text: String) {
        val number = to.filter { it.isDigit() || it == '+' }
        if (number.isEmpty() || text.isBlank()) return
        if (!canSend()) { denied(); return }
        val sm = smsManager()
        val parts = sm.divideMessage(text)
        if (parts.size > 1) sm.sendMultipartTextMessage(number, null, parts, null, null)
        else sm.sendTextMessage(number, null, text, null, null)
        post(JSONObject().put("t", "sms-sent").put("to", number).put("ok", true))
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    private fun denied() {
        post(JSONObject().put("t", "event").put("err", true)
            .put("text", "Izin SMS belum diberikan. Buka app SCRCTL Link di HP, bagian Izin."))
    }
}
