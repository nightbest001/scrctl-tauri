package app.scrctl.link

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Kamera HP -> PC. PC kirim {t:"cam", a:"start"|"stop"|"switch", facing:"back"|"front"}.
 * Frame JPEG dikirim sebagai frame jenis 2: [putaran/90][cermin 0/1][JPEG...].
 * HP balas {t:"cam-state", on, facing, err?}.
 */
class PhoneCamera(
    ctx: Context,
    private val post: (JSONObject) -> Unit,
    private val sendFrame: (Int, ByteArray) -> Unit,
) {
    private val ctx = ctx.applicationContext
    private val cm = this.ctx.getSystemService(CameraManager::class.java)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile private var on = false
    @Volatile private var facing = "back"
    @Volatile private var rot = 0
    @Volatile private var mirror = 0
    private var lastFrame = 0L

    fun allowed(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun handle(o: JSONObject) {
        when (o.optString("a")) {
            "start" -> start(if (o.optString("facing") == "front") "front" else "back")
            "switch" -> start(if (facing == "back") "front" else "back")
            "stop" -> { stop(); pushState(null) }
            else -> pushState(null)
        }
    }

    fun pushState(err: String?) {
        val o = JSONObject().put("t", "cam-state").put("on", on).put("facing", facing)
        if (err != null) o.put("err", err)
        post(o)
    }

    @SuppressLint("MissingPermission")
    private fun start(face: String) {
        stop()
        if (!allowed()) { pushState("perm"); return }
        try {
            val want = if (face == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            if (id == null) { pushState("none"); return }
            val ch = cm.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val size = sizes.filter { it.width <= 1280 && it.width * it.height <= 1280 * 720 }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { it.width * it.height }
            if (size == null) { pushState("none"); return }

            facing = face
            rot = ((ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) / 90) % 4
            mirror = if (face == "front") 1 else 0

            val t = HandlerThread("scrctl-cam").also { it.start() }
            thread = t
            val h = Handler(t.looper)
            handler = h

            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrame < 66) return@setOnImageAvailableListener // maks ~15 fps
                    lastFrame = now
                    val jpg = toJpeg(img)
                    val body = ByteArray(2 + jpg.size)
                    body[0] = rot.toByte(); body[1] = mirror.toByte()
                    System.arraycopy(jpg, 0, body, 2, jpg.size)
                    sendFrame(2, body)
                } catch (_: Exception) {
                } finally {
                    img.close()
                }
            }, h)

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    device = cd
                    try {
                        @Suppress("DEPRECATION")
                        cd.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(r.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    s.setRepeatingRequest(req.build(), null, h)
                                    on = true
                                    pushState(null)
                                } catch (e: Exception) { fail("gagal: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail("konfigurasi gagal") }
                        }, h)
                    } catch (e: Exception) { fail("gagal: ${e.message}") }
                }
                override fun onDisconnected(cd: CameraDevice) { fail("kamera dipakai app lain") }
                override fun onError(cd: CameraDevice, error: Int) { fail("error kamera $error") }
            }, h)
        } catch (e: Exception) {
            fail("gagal: ${e.message}")
        }
    }

    private fun fail(msg: String) {
        stop()
        LinkHub.log("Kamera: $msg", true)
        pushState(msg)
    }

    fun stop() {
        on = false
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        try { thread?.quitSafely() } catch (_: Exception) {}
        thread = null; handler = null
    }

    // ---- YUV_420_888 -> JPEG ----
    private fun toJpeg(img: Image): ByteArray {
        val w = img.width
        val h = img.height
        val nv = ByteArray(w * h * 3 / 2)
        val yp = img.planes[0]
        val yb = yp.buffer
        for (row in 0 until h) {
            yb.position(row * yp.rowStride)
            yb.get(nv, row * w, w)
        }
        val up = img.planes[1]
        val vp = img.planes[2]
        val ub = up.buffer
        val vb = vp.buffer
        var pos = w * h
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv[pos++] = vb.get(row * vp.rowStride + col * vp.pixelStride)
                nv[pos++] = ub.get(row * up.rowStride + col * up.pixelStride)
            }
        }
        val out = ByteArrayOutputStream(64 * 1024)
        YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 65, out)
        return out.toByteArray()
    }
}
