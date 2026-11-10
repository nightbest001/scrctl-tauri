package app.scrctl.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** Ukuran layar asli dalam piksel dan kerapatan: [lebar, tinggi, dpi]. */
fun realScreenSize(ctx: Context): IntArray {
    val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val dpi = ctx.resources.displayMetrics.densityDpi
    return if (Build.VERSION.SDK_INT >= 30) {
        val b = wm.currentWindowMetrics.bounds
        intArrayOf(b.width(), b.height(), dpi)
    } else {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        intArrayOf(m.widthPixels, m.heightPixels, dpi)
    }
}

/** Menangkap layar HP ini (MediaProjection) dan mengirim frame JPEG lewat [start]. */
class ScreenShare(private val ctx: Context) {

    @Volatile var projection: MediaProjection? = null
    @Volatile var active = false
    @Volatile var width = 0
    @Volatile var height = 0

    /** Dipanggil kalau pengguna menghentikan tangkapan layar dari sistem. */
    var onStopped: (() -> Unit)? = null

    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var handler: Handler? = null
    private var pending: Image? = null

    fun attach(p: MediaProjection) {
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
                projection = null
                onStopped?.invoke()
            }
        }, Handler(Looper.getMainLooper()))
    }

    @Synchronized
    fun start(maxWidth: Int, fps: Int, quality: Int, sink: (ByteArray) -> Unit): Boolean {
        val p = projection ?: return false
        stop()
        val real = realScreenSize(ctx)
        val scale = minOf(1f, maxWidth.toFloat() / real[0])
        val w = ((real[0] * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((real[1] * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val ht = HandlerThread("orbit-screen").also { it.start() }
        val hd = Handler(ht.looper)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        val gap = 1000L / fps.coerceIn(1, 30)
        val q = quality.coerceIn(20, 90)
        var last = 0L
        var scheduled = false

        fun process(img: Image) {
            try {
                val pl = img.planes[0]
                val rowPad = pl.rowStride - pl.pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPad / pl.pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(pl.buffer)
                val crop = if (rowPad > 0) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                val out = ByteArrayOutputStream(64 * 1024)
                crop.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (crop !== bmp) crop.recycle()
                bmp.recycle()
                sink(out.toByteArray())
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }

        // Frame terbaru ditahan sebentar supaya batas fps terpenuhi tanpa kehilangan perubahan terakhir.
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            pending?.close()
            pending = img
            if (scheduled) return@setOnImageAvailableListener
            scheduled = true
            val wait = maxOf(0L, gap - (SystemClock.uptimeMillis() - last))
            hd.postDelayed({
                scheduled = false
                val i = pending
                pending = null
                if (i != null) {
                    last = SystemClock.uptimeMillis()
                    process(i)
                }
            }, wait)
        }, hd)

        val vd = try {
            p.createVirtualDisplay(
                "orbit-link", w, h, real[2], DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, hd
            )
        } catch (_: Exception) {
            null
        }
        if (vd == null) {
            r.close()
            ht.quitSafely()
            return false
        }
        thread = ht
        handler = hd
        reader = r
        display = vd
        width = w
        height = h
        active = true
        return true
    }

    @Synchronized
    fun stop() {
        active = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        handler?.removeCallbacksAndMessages(null)
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { pending?.close() } catch (_: Exception) {}
        pending = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun release() {
        stop()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }
}
