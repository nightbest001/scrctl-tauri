package app.scrctl.link

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Kontrol PC dari HP: touchpad, layar PC, keyboard, pintasan, dan media. */
class ControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            OrbitTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Orbit.Bg) {
                    ControlScreen(onBack = { finish() })
                }
            }
        }
    }

    override fun onStop() {
        LinkHub.pc.stopScreen()
        super.onStop()
    }
}

private data class Quality(val label: String, val maxWidth: Int, val fps: Int, val q: Int)

private val QUALITIES = listOf(
    Quality("Hemat", 960, 8, 40),
    Quality("Normal", 1280, 12, 55),
    Quality("Tajam", 1600, 15, 70),
)

private data class Shortcut(val label: String, val key: String, val mods: List<String> = emptyList())

private val SHORTCUTS = listOf(
    Shortcut("Salin", "c", listOf("ctrl")),
    Shortcut("Tempel", "v", listOf("ctrl")),
    Shortcut("Potong", "x", listOf("ctrl")),
    Shortcut("Urungkan", "z", listOf("ctrl")),
    Shortcut("Ulangi", "y", listOf("ctrl")),
    Shortcut("Pilih semua", "a", listOf("ctrl")),
    Shortcut("Simpan", "s", listOf("ctrl")),
    Shortcut("Cari", "f", listOf("ctrl")),
    Shortcut("Ganti jendela", "tab", listOf("alt")),
    Shortcut("Desktop", "d", listOf("win")),
    Shortcut("Menu Start", "win"),
    Shortcut("Tutup jendela", "f4", listOf("alt")),
    Shortcut("Tab baru", "t", listOf("ctrl")),
    Shortcut("Muat ulang", "f5"),
    Shortcut("Layar penuh", "f11"),
    Shortcut("Tangkapan layar", "s", listOf("win", "shift")),
    Shortcut("Task Manager", "esc", listOf("ctrl", "shift")),
)

@Composable
private fun ControlScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val remote = LinkHub.pc
    val ui by LinkHub.ui.collectAsState()
    val pc by remote.state.collectAsState()
    val frame by remote.frame.collectAsState()
    val connected = ui.conn == Conn.Connected

    val phone = pc.kind == "phone"
    var modeSel by rememberSaveable { mutableStateOf(0) } // 0 touchpad, 1 layar PC
    val mode = if (phone) 1 else modeSel // HP target selalu lewat layar: sentuh langsung
    var panel by rememberSaveable { mutableStateOf(0) } // 0 ketik, 1 pintasan, 2 media, 3 setelan
    var immersive by rememberSaveable { mutableStateOf(false) }
    var speed by remember { mutableStateOf(Prefs.padSpeed(ctx)) }
    var natural by remember { mutableStateOf(Prefs.naturalScroll(ctx)) }
    var quality by remember { mutableStateOf(Prefs.screenQuality(ctx).coerceIn(0, QUALITIES.size - 1)) }
    val allowed = connected && pc.control != false

    LaunchedEffect(connected) { if (connected) remote.requestState() }

    // Layar PC hanya dikirim selama mode "Layar PC" aktif dan layar ini terbuka.
    val streaming = allowed && mode == 1
    DisposableEffect(streaming, quality) {
        if (streaming) {
            val q = QUALITIES[quality]
            remote.startScreen(q.maxWidth, q.fps, q.q)
        }
        onDispose { if (streaming) remote.stopScreen() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (!immersive || !allowed) {
            TopBar(
                title = if (phone) "Kontrol HP" else "Kontrol PC",
                subtitle = ui.pc ?: "Tidak terhubung",
                mode = mode,
                onMode = { modeSel = it },
                onBack = onBack,
                showMode = allowed && !phone,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (!allowed) {
            Blocked(connected = connected, onBack = onBack)
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (mode == 0) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        PadSurface(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            absolute = false, frame = null, speed = speed, natural = natural,
                        )
                        Spacer(Modifier.width(8.dp))
                        ScrollStrip(natural = natural)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HoldButton("Klik kiri", "left", Modifier.weight(1f))
                        HoldButton("Klik tengah", "middle", Modifier.weight(0.7f))
                        HoldButton("Klik kanan", "right", Modifier.weight(1f))
                    }
                }
            } else {
                PadSurface(
                    modifier = Modifier.fillMaxSize(),
                    absolute = true, frame = frame, speed = speed, natural = natural, phone = phone,
                )
                if (phone && !pc.input) {
                    Text(
                        "Layanan Aksesibilitas di HP target belum aktif. Layar tampil, tapi sentuhan belum bisa dijalankan.",
                        style = OrbitText.Caption, color = Orbit.Warn,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    )
                }
                if (frame == null) {
                    Text(
                        if (phone) "Menyambung ke layar HP…" else "Menyambung ke layar PC…",
                        style = OrbitText.Caption,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC16171B))
                        .clickable { immersive = !immersive }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(if (immersive) "Keluar layar penuh" else "Layar penuh", style = OrbitText.Button, color = Orbit.Text)
                }
            }
        }

        if (!immersive) {
            Spacer(Modifier.height(8.dp))
            val panels = if (phone) listOf("Navigasi", "Ketik", "Media", "Setelan") else listOf("Ketik", "Pintasan", "Media", "Setelan")
            Segmented(panels, panel, { panel = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 210.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (panels[panel]) {
                    "Navigasi" -> NavPanel()
                    "Ketik" -> TypePanel()
                    "Pintasan" -> ShortcutPanel()
                    "Media" -> MediaPanel()
                    else -> SettingsPanel(
                        speed = speed, onSpeed = { speed = it; Prefs.setPadSpeed(ctx, it) },
                        natural = natural, onNatural = { natural = it; Prefs.setNaturalScroll(ctx, it) },
                        quality = quality, onQuality = { quality = it; Prefs.setScreenQuality(ctx, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, mode: Int, onMode: (Int) -> Unit, onBack: () -> Unit, showMode: Boolean) {
    val wide = LocalConfiguration.current.screenWidthDp >= 560
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) { Text("Kembali", style = OrbitText.Button, color = Orbit.Accent) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = OrbitText.Heading)
                Text(subtitle, style = OrbitText.Caption, maxLines = 1)
            }
            if (showMode && wide) {
                Segmented(listOf("Touchpad", "Layar PC"), mode, onMode, Modifier.width(240.dp))
            }
        }
        if (showMode && !wide) {
            Spacer(Modifier.height(8.dp))
            Segmented(listOf("Touchpad", "Layar PC"), mode, onMode, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Blocked(connected: Boolean, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (!connected) "Belum terhubung ke PC" else "Kontrol PC dimatikan",
            style = OrbitText.Heading,
        )
        Text(
            if (!connected) "Sambungkan HP ke Orbit di PC dulu, lalu buka halaman ini lagi."
            else "Nyalakan di Orbit untuk Windows: menu Link HP → Kontrol dari HP.",
            style = OrbitText.Caption,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        SecondaryButton("Kembali", onBack)
    }
}

// ---------------------------------------------------------------------------
// Permukaan sentuh: touchpad (gerak relatif) dan layar PC (posisi mutlak)
// ---------------------------------------------------------------------------

/** Area gambar dengan rasio asli di dalam kotak: (kiri, atas, lebar, tinggi). */
private fun fitRect(boxW: Float, boxH: Float, imgW: Float, imgH: Float): FloatArray {
    if (boxW <= 0f || boxH <= 0f || imgW <= 0f || imgH <= 0f) return floatArrayOf(0f, 0f, maxOf(boxW, 1f), maxOf(boxH, 1f))
    val scale = minOf(boxW / imgW, boxH / imgH)
    val w = imgW * scale
    val h = imgH * scale
    return floatArrayOf((boxW - w) / 2f, (boxH - h) / 2f, w, h)
}

@Composable
private fun PadSurface(
    modifier: Modifier,
    absolute: Boolean,
    frame: ScreenFrame?,
    speed: Int,
    natural: Boolean,
    phone: Boolean = false,
) {
    val remote = LinkHub.pc
    val haptic = LocalHapticFeedback.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    val boxNow by rememberUpdatedState(box)
    val frameNow by rememberUpdatedState(frame)
    val speedNow by rememberUpdatedState(speed)
    val naturalNow by rememberUpdatedState(natural)
    var lastTapUp by remember { mutableLongStateOf(0L) }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (absolute) Color.Black else Orbit.Surface)
            .border(1.dp, Orbit.Line, shape)
            .onSizeChanged { box = it }
            .pointerInput(absolute, phone) {
                val slop = viewConfiguration.touchSlop

                // posisi sentuh -> 0..1 terhadap gambar layar PC
                fun norm(p: Offset): Offset {
                    val b = boxNow
                    val f = frameNow
                    val r = if (f == null) {
                        floatArrayOf(0f, 0f, b.width.toFloat(), b.height.toFloat())
                    } else {
                        fitRect(b.width.toFloat(), b.height.toFloat(), f.bitmap.width.toFloat(), f.bitmap.height.toFloat())
                    }
                    val x = if (r[2] > 0f) (p.x - r[0]) / r[2] else 0f
                    val y = if (r[3] > 0f) (p.y - r[1]) / r[3] else 0f
                    return Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val t0 = SystemClock.uptimeMillis()
                    val start = down.position
                    var last = start
                    var maxPointers = 1
                    var moved = false
                    var scrolled = false
                    var dragging = false
                    var pendingX = 0f
                    var pendingY = 0f
                    var scrollAcc = 0f
                    var twoTotal = 0f
                    var lastSend = t0
                    val armedDrag = !absolute && (t0 - lastTapUp) < 300L
                    val k = when (speedNow) { 1 -> 1.0f; 3 -> 2.6f; else -> 1.7f }

                    fun flushMove(force: Boolean) {
                        val now = SystemClock.uptimeMillis()
                        if (!force && now - lastSend < 8L) return
                        val ix = pendingX.toInt()
                        val iy = pendingY.toInt()
                        if (ix != 0 || iy != 0) {
                            remote.move(ix, iy)
                            pendingX -= ix
                            pendingY -= iy
                        }
                        lastSend = now
                    }

                    fun sendAbs(p: Offset, force: Boolean) {
                        val now = SystemClock.uptimeMillis()
                        if (!force && now - lastSend < 12L) return
                        val n = norm(p)
                        remote.abs(n.x, n.y)
                        lastSend = now
                    }

                    while (true) {
                        val waitLongPress = absolute && !dragging && !moved && maxPointers == 1
                        val event = if (waitLongPress) {
                            val remaining = 350L - (SystemClock.uptimeMillis() - t0)
                            if (remaining <= 0L) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            // tahan tanpa geser di layar PC = mulai seret (tombol kiri ditahan)
                            sendAbs(start, true)
                            remote.button("left", true)
                            dragging = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            event.changes.forEach { it.consume() }
                            break
                        }
                        if (pressed.size > maxPointers) maxPointers = pressed.size

                        if (pressed.size >= 2) {
                            // dua jari: gulir
                            var dy = 0f
                            for (c in pressed) dy += c.positionChange().y
                            dy /= pressed.size
                            twoTotal += abs(dy)
                            if (twoTotal > slop) scrolled = true
                            if (scrolled) {
                                scrollAcc += dy * 4f
                                val units = scrollAcc.toInt()
                                if (abs(units) >= 10) {
                                    remote.scroll(0, if (naturalNow) units else -units)
                                    scrollAcc -= units
                                }
                            }
                        } else if (maxPointers == 1) {
                            val c = pressed[0]
                            last = c.position
                            if (!moved && (c.position - start).getDistance() > slop) {
                                moved = true
                                if (armedDrag) {
                                    remote.button("left", true)
                                    dragging = true
                                } else if (absolute && phone && maxPointers == 1 && !dragging) {
                                    // layar HP: geser langsung = seret/geser di HP target
                                    sendAbs(start, true)
                                    remote.button("left", true)
                                    dragging = true
                                }
                            }
                            if (absolute) {
                                if (moved || dragging) sendAbs(c.position, false)
                            } else if (moved) {
                                val d = c.positionChange()
                                pendingX += d.x * k
                                pendingY += d.y * k
                                flushMove(false)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }

                    val elapsed = SystemClock.uptimeMillis() - t0
                    if (absolute && (moved || dragging)) sendAbs(last, true) else flushMove(true)
                    if (dragging) {
                        remote.button("left", false)
                    } else if (!moved && !scrolled && elapsed < 300L) {
                        if (absolute) sendAbs(start, true)
                        when {
                            maxPointers == 1 -> {
                                remote.click("left")
                                lastTapUp = SystemClock.uptimeMillis()
                            }
                            maxPointers == 2 -> remote.click("right")
                            else -> remote.click("middle")
                        }
                    }
                }
            },
    ) {
        if (absolute) {
            val f = frame
            if (f != null) {
                val img = remember(f.seq) { f.bitmap.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    val r = fitRect(size.width, size.height, img.width.toFloat(), img.height.toFloat())
                    drawImage(
                        image = img,
                        dstOffset = IntOffset(r[0].toInt(), r[1].toInt()),
                        dstSize = IntSize(r[2].toInt().coerceAtLeast(1), r[3].toInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Geser untuk menggerakkan kursor", style = OrbitText.Caption)
                Text("Ketuk = klik · Dua jari = gulir · Ketuk dua jari = klik kanan", style = OrbitText.Caption, modifier = Modifier.padding(top = 4.dp))
                Text("Ketuk lalu seret = tahan dan seret", style = OrbitText.Caption, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Jalur gulir di sisi kanan touchpad: seret naik/turun untuk menggulir. */
@Composable
private fun ScrollStrip(natural: Boolean) {
    val remote = LinkHub.pc
    val naturalNow by rememberUpdatedState(natural)
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(Orbit.Surface)
            .border(1.dp, Orbit.Line, shape)
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        acc += dragAmount * 4f
                        val units = acc.toInt()
                        if (abs(units) >= 10) {
                            remote.scroll(0, if (naturalNow) units else -units)
                            acc -= units
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Gulir", style = OrbitText.Label)
    }
}

/** Tombol mouse yang ditahan selama jari menempel (bisa dipakai untuk seret). */
@Composable
private fun HoldButton(label: String, button: String, modifier: Modifier) {
    val remote = LinkHub.pc
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) Orbit.Accent else Orbit.Raised)
            .border(1.dp, if (pressed) Orbit.Accent else Orbit.Line, RoundedCornerShape(10.dp))
            .pointerInput(button) {
                detectTapGestures(onPress = {
                    pressed = true
                    remote.button(button, true)
                    try {
                        tryAwaitRelease()
                    } finally {
                        remote.button(button, false)
                        pressed = false
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OrbitText.Button, color = if (pressed) Color.White else Orbit.Text)
    }
}

// ---------------------------------------------------------------------------
// Panel bawah
// ---------------------------------------------------------------------------
private fun commonPrefix(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}

@Composable
private fun TypePanel() {
    val remote = LinkHub.pc
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var prev by remember { mutableStateOf("") }
    val mods = remember { mutableStateOf(setOf<String>()) }

    fun press(k: String) {
        remote.key(k, mods.value.toList())
        mods.value = emptySet()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Teks yang diketik langsung dikirim ke PC. Hapus di sini = Backspace di PC.
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Orbit.Surface)
                .border(1.dp, Orbit.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { nv ->
                    val text = nv.text
                    val common = commonPrefix(prev, text)
                    val removed = prev.length - common
                    val added = text.substring(common)
                    if (removed == 0 && added.length == 1 && mods.value.isNotEmpty()) {
                        press(added.lowercase())
                    } else {
                        repeat(removed) { remote.key("backspace") }
                        remote.text(added)
                    }
                    if (text.length > 80) {
                        field = TextFieldValue("")
                        prev = ""
                    } else {
                        field = nv
                        prev = text
                    }
                },
                singleLine = true,
                textStyle = OrbitText.Body,
                cursorBrush = SolidColor(Orbit.Accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { press("enter") }),
                decorationBox = { inner ->
                    if (field.text.isEmpty()) Text("Ketik di sini, langsung muncul di PC", style = OrbitText.Caption)
                    inner()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in listOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "win" to "Win")) {
                KeyButton(m.second, Modifier.weight(1f), active = m.first in mods.value) {
                    mods.value = if (m.first in mods.value) mods.value - m.first else mods.value + m.first
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton("Esc", Modifier.weight(1f)) { press("esc") }
            KeyButton("Tab", Modifier.weight(1f)) { press("tab") }
            KeyButton("Enter", Modifier.weight(1.2f)) { press("enter") }
            KeyButton("Hapus", Modifier.weight(1.2f)) { press("backspace") }
            KeyButton("Del", Modifier.weight(1f)) { press("delete") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in listOf("left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "home" to "Home", "end" to "End", "pageup" to "PgUp", "pagedown" to "PgDn")) {
                KeyButton(k.second, Modifier.width(64.dp)) { press(k.first) }
            }
        }
    }
}

@Composable
private fun NavPanel() {
    val remote = LinkHub.pc
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("Kembali", Modifier.weight(1f)) { remote.nav("back") }
            KeyButton("Beranda", Modifier.weight(1f)) { remote.nav("home") }
            KeyButton("Terbaru", Modifier.weight(1f)) { remote.nav("recents") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("Notifikasi", Modifier.weight(1f)) { remote.nav("notif") }
            KeyButton("Pengaturan cepat", Modifier.weight(1.3f)) { remote.nav("quick") }
            KeyButton("Kunci layar", Modifier.weight(1f)) { remote.nav("lock") }
        }
        Text("Ketuk layar = klik. Geser = geser di HP target. Tahan lalu lepas = tekan lama. Ketuk dua jari = Kembali.", style = OrbitText.Caption)
    }
}

@Composable
private fun ShortcutPanel() {
    val remote = LinkHub.pc
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in SHORTCUTS.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in row) KeyButton(s.label, Modifier.weight(1f)) { remote.key(s.key, s.mods) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MediaPanel() {
    val remote = LinkHub.pc
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("Sebelumnya", Modifier.weight(1f)) { remote.key("prev") }
            KeyButton("Putar / Jeda", Modifier.weight(1.2f)) { remote.key("play_pause") }
            KeyButton("Berikutnya", Modifier.weight(1f)) { remote.key("next") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("Volume −", Modifier.weight(1f)) { remote.key("vol_down") }
            KeyButton("Bisukan", Modifier.weight(1f)) { remote.key("mute") }
            KeyButton("Volume +", Modifier.weight(1f)) { remote.key("vol_up") }
        }
    }
}

@Composable
private fun SettingsPanel(
    speed: Int, onSpeed: (Int) -> Unit,
    natural: Boolean, onNatural: (Boolean) -> Unit,
    quality: Int, onQuality: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Kecepatan kursor", style = OrbitText.Caption)
        Segmented(listOf("Lambat", "Normal", "Cepat"), speed - 1, { onSpeed(it + 1) }, Modifier.fillMaxWidth())
        Text("Kualitas layar PC", style = OrbitText.Caption)
        Segmented(QUALITIES.map { it.label }, quality, onQuality, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gulir natural", style = OrbitText.Body)
                Text("Jari naik = halaman bergerak naik", style = OrbitText.Caption)
            }
            OrbitSwitch(natural, onNatural)
        }
    }
}
