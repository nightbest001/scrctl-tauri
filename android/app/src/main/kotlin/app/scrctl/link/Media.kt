package app.scrctl.link

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Musik/video yang sedang diputar di HP (sesi media app lain), dikirim ke PC dan bisa dikontrol dari PC.
 * Pakai izin "Akses notifikasi" yang sudah dipakai NotifListener.
 */
class PhoneMedia(private val ctx: Context, private val post: (JSONObject) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val comp = ComponentName(ctx, NotifListener::class.java)
    private val watched = HashMap<MediaController, MediaController.Callback>()
    @Volatile private var current: MediaController? = null
    private var running = false
    private var scheduled = false
    private var lastTrackKey = ""
    private var warned = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }
    private val publishTask = Runnable { scheduled = false; publish() }

    fun start() = main.post {
        if (running) return@post
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, comp, main)
            running = true
            watch(msm.getActiveSessions(comp))
        } catch (e: SecurityException) {
            running = false
            if (!warned) {
                warned = true
                post(JSONObject().put("t", "event").put("err", true)
                    .put("text", "Musik HP butuh izin Akses notifikasi di app Orbit Link"))
            }
        }
    }

    fun stop() = main.post {
        if (!running) return@post
        running = false
        try { msm.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) {}
        watched.forEach { (c, cb) -> try { c.unregisterCallback(cb) } catch (_: Exception) {} }
        watched.clear()
        current = null
        lastTrackKey = ""
        main.removeCallbacks(publishTask)
        scheduled = false
    }

    /** Dipanggil saat PC baru terhubung: kirim ulang status penuh termasuk gambar. */
    fun resend() = main.post { lastTrackKey = ""; publish() }

    private fun watch(list: List<MediaController>) {
        val now = list.filter { it.packageName != ctx.packageName }
        watched.keys.filter { old -> now.none { it.sessionToken == old.sessionToken } }.forEach { gone ->
            watched.remove(gone)?.let { try { gone.unregisterCallback(it) } catch (_: Exception) {} }
        }
        for (c in now) {
            if (watched.keys.any { it.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
                override fun onPlaybackStateChanged(state: PlaybackState?) = schedule()
                override fun onSessionDestroyed() = schedule()
            }
            c.registerCallback(cb, main)
            watched[c] = cb
        }
        schedule()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        main.postDelayed(publishTask, 250)
    }

    private fun pick(): MediaController? {
        val all = watched.keys.toList()
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.metadata != null && it.playbackState?.state != PlaybackState.STATE_NONE }
    }

    private fun label(pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun publish() {
        val c = pick()
        current = c
        val md = c?.metadata
        if (c == null || md == null) {
            lastTrackKey = ""
            post(JSONObject().put("t", "media").put("has", false))
            return
        }
        val st = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val playing = st?.state == PlaybackState.STATE_PLAYING
        var pos = st?.position ?: 0L
        if (st != null && playing && pos >= 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        val key = "${c.packageName}|$title|$artist"
        val o = JSONObject().put("t", "media").put("has", true)
            .put("pkg", c.packageName).put("app", label(c.packageName))
            .put("title", title).put("artist", artist)
            .put("playing", playing).put("pos", pos.coerceAtLeast(0)).put("dur", dur.coerceAtLeast(0))
            .put("key", key)
        if (key != lastTrackKey) {
            lastTrackKey = key
            val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null) o.put("art", encode(bmp))
        }
        post(o)
    }

    private fun encode(src: Bitmap): String {
        val side = 160
        val s = minOf(side.toFloat() / src.width, side.toFloat() / src.height).coerceAtMost(1f)
        val bmp = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Perintah dari PC: toggle, play, pause, next, prev, seek. */
    fun command(o: JSONObject) = main.post {
        val c = current ?: pick() ?: return@post
        val tc = c.transportControls
        when (o.optString("a")) {
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            "play" -> tc.play()
            "pause" -> tc.pause()
            "next" -> tc.skipToNext()
            "prev" -> tc.skipToPrevious()
            "seek" -> tc.seekTo(o.optLong("pos"))
        }
    }
}
