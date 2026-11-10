package app.scrctl.link

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val resumeTick = mutableIntStateOf(0)
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeTick.intValue++
        // Mulai ulang service supaya tipe foreground "camera" ikut aktif
        if (Prefs.enabled(this)) ContextCompat.startForegroundService(this, Intent(this, LinkService::class.java))
        LinkHub.client?.features?.handle(org.json.JSONObject().put("t", "caps"))
    }
    private val askFeatures = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        resumeTick.intValue++
        LinkHub.client?.features?.handle(org.json.JSONObject().put("t", "caps"))
    }

    private fun featurePerms(): Array<String> = buildList {
        add(Manifest.permission.READ_SMS); add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CONTACTS); add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.CALL_PHONE); add(Manifest.permission.ANSWER_PHONE_CALLS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES)
        else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrbitTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Orbit.Bg) { Home() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++ // izin bisa berubah saat user kembali dari pengaturan
    }

    // ---- aksi ----
    private fun connect(host: String, port: Int, token: String) {
        Prefs.save(this, host, port, token)
        Prefs.setEnabled(this, true)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        ContextCompat.startForegroundService(this, Intent(this, LinkService::class.java))
    }

    private fun disconnect() {
        Prefs.setEnabled(this, false)
        stopService(Intent(this, LinkService::class.java))
        LinkHub.setConn(Conn.Stopped, null, null)
    }

    private fun connectedClient(): LinkClient? {
        val c = LinkHub.client
        return if (c != null && LinkHub.ui.value.conn == Conn.Connected) c else null
    }

    private fun sendFiles(uris: List<Uri>) {
        val c = connectedClient()
        if (c == null) toast("Belum terhubung ke PC") else if (uris.isNotEmpty()) c.sendUris(uris)
    }

    private fun sendClipboard() {
        val c = connectedClient()
        if (c == null) { toast("Belum terhubung ke PC"); return }
        val t = Clip.getText(this)
        if (t == null) toast("Clipboard HP kosong") else { c.sendClipboardText(t); toast("Clipboard dikirim ke PC") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun open(action: String, pkgUri: Boolean = false) {
        try {
            val i = Intent(action)
            if (pkgUri) i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            toast("Tidak bisa membuka pengaturan")
        }
    }

    private fun parseHost(raw: String): Pair<String, Int>? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val i = t.lastIndexOf(':')
        if (i < 0) return t to 5588
        val p = t.substring(i + 1).toIntOrNull() ?: return null
        return t.substring(0, i) to p
    }

    // ---- UI ----
    @Composable
    private fun Home() {
        val ctx = LocalContext.current
        val ui by LinkHub.ui.collectAsState()

        var host by remember {
            val h = Prefs.host(ctx)
            val p = Prefs.port(ctx)
            mutableStateOf(if (h.isEmpty()) "" else if (p == 5588) h else "$h:$p")
        }
        var code by remember { mutableStateOf(Prefs.token(ctx)) }

        var upd by remember { mutableStateOf<Updater.Info?>(null) }
        LaunchedEffect(Unit) { upd = withContext(Dispatchers.IO) { Updater.check(ctx) } }

        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            sendFiles(uris)
        }
        val connected = ui.conn == Conn.Connected

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column {
                Text("Orbit Link", style = OrbitText.Title)
                Text("Versi ${Updater.currentVersion(ctx)}", style = OrbitText.Caption, modifier = Modifier.padding(top = 2.dp))
            }

            upd?.let { u ->
                Section {
                    ListRow(
                        title = "Update tersedia: ${u.tag}",
                        subtitle = "Unduh APK baru lalu pasang. Data dan pengaturan tetap.",
                        trailing = {
                            TextAction("Unduh", {
                                val url = u.apkUrl.ifEmpty { u.pageUrl }
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast("Tidak bisa membuka link") }
                            })
                        },
                    )
                }
            }

            // --- koneksi ---
            Section("Koneksi") {
                val (dot, label) = when (ui.conn) {
                    Conn.Connected -> Orbit.Ok to "Terhubung ke ${ui.pc ?: "PC"}"
                    Conn.Connecting -> Orbit.Accent to "Menghubungkan"
                    Conn.Waiting -> Orbit.Warn to "Mencoba menyambung lagi"
                    Conn.AuthFailed -> Orbit.Danger to "Kode ditolak"
                    Conn.Stopped -> Orbit.TextDim to "Tidak terhubung"
                }
                ListRow(
                    title = label,
                    subtitle = ui.message.takeIf { it.isNotEmpty() && !connected },
                    trailing = { StatusDot(dot) },
                )
                RowDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val idle = ui.conn == Conn.Stopped || ui.conn == Conn.AuthFailed
                    if (idle) {
                        Text("Buka Orbit di PC, menu Link HP, lalu isi alamat dan kode yang tampil di sana. Dari luar WiFi, pakai alamat ngrok (contoh 0.tcp.ap.ngrok.io:12345). Untuk HP lain, isi alamat dan kode dari layar HP ini bisa dikontrol.", style = OrbitText.Caption)
                        OutlinedTextField(
                            value = host, onValueChange = { host = it },
                            label = { Text("Alamat PC") },
                            placeholder = { Text("192.168.1.10") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = code, onValueChange = { code = it },
                            label = { Text("Kode dari Orbit di PC") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = fieldColors(),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        )
                        PrimaryButton("Hubungkan", {
                            val hp = parseHost(host)
                            val tk = Proto.normalizeToken(code)
                            if (hp == null || tk.length < 8 || tk.length > 16) toast("Isi alamat PC dan kode dari Orbit")
                            else connect(hp.first, hp.second, tk)
                        }, Modifier.fillMaxWidth())
                    } else {
                        SecondaryButton("Putuskan", { disconnect() }, Modifier.fillMaxWidth())
                    }
                }
            }

            // --- kontrol PC ---
            Section("Kontrol PC", note = "Perlu dinyalakan di Orbit untuk Windows: menu Link HP, bagian Kontrol dari HP.") {
                ListRow(
                    title = "Touchpad, keyboard, dan layar PC",
                    subtitle = if (connected) "Kendalikan PC dari HP ini" else "Sambungkan ke PC dulu",
                    enabled = connected,
                    onClick = { startActivity(Intent(this@MainActivity, ControlActivity::class.java)) },
                    trailing = { TextAction("Buka", { startActivity(Intent(this@MainActivity, ControlActivity::class.java)) }, connected) },
                )
            }

            // --- HP ini dikontrol HP lain ---
            Section("Kontrol HP ke HP", note = "Jadikan HP ini bisa dikontrol HP lain lewat hotspot atau WiFi yang sama. Pengontrol memasukkan alamat dan kode yang tampil di sana.") {
                ListRow(
                    title = "HP ini bisa dikontrol",
                    subtitle = "Tampilkan alamat dan kode",
                    onClick = { startActivity(Intent(this@MainActivity, HostActivity::class.java)) },
                    trailing = { TextAction("Buka", { startActivity(Intent(this@MainActivity, HostActivity::class.java)) }) },
                )
            }

            // --- kirim ke PC ---
            Section(
                "Kirim ke PC",
                note = "Bisa juga lewat menu Bagikan, pilih Orbit Link. Clipboard PC ke HP tersinkron otomatis; " +
                    "HP ke PC harus ditekan manual karena Android melarang app latar belakang membaca clipboard.",
            ) {
                ListRow("Kirim file", "Pilih satu atau beberapa file", enabled = connected, onClick = { pickFiles.launch(arrayOf("*/*")) })
                RowDivider()
                ListRow("Kirim clipboard HP", "Salin teks di HP ke clipboard PC", enabled = connected, onClick = { sendClipboard() })
            }

            // --- izin ---
            @Suppress("UNUSED_VARIABLE")
            val tick = resumeTick.intValue // dibaca di sini supaya status izin dihitung ulang tiap kembali ke app
            val listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val dnd = nm.isNotificationPolicyAccessGranted
            val battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            val postOk = Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val featuresOk = featurePerms().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            val camOk = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

            Section(
                "Izin dan fitur",
                note = if (!listener || !featuresOk)
                    "Android 13 ke atas: kalau dialog izin tidak muncul atau pilihannya abu-abu, buka Info app, ketuk menu titik tiga, " +
                        "pilih Izinkan setelan terbatas, lalu ulangi."
                else null,
            ) {
                AllowRow("Notifikasi HP ke PC", "Akses notifikasi", listener, "notif", {
                    open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }) { }
                RowDivider()
                AllowRow("Jangan Ganggu", "Dikontrol dari PC", dnd, "dnd", {
                    open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                }) { }
                RowDivider()
                AllowRow("Notifikasi status", "Menampilkan status koneksi", postOk, "status", {
                    if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { }
                RowDivider()
                AllowRow("Pesan, kontak, telepon, foto", "Untuk halaman Pesan, Telepon, dan Foto di PC", featuresOk, "features", {
                    askFeatures.launch(featurePerms())
                }) { on ->
                    LinkHub.client?.features?.setFeaturesAllowed(on)
                }
                RowDivider()
                AllowRow("Kamera", "Pakai kamera HP dari PC", camOk, "camera", { askCamera.launch(Manifest.permission.CAMERA) }) { on ->
                    if (!on) LinkHub.client?.features?.camera?.let { it.stop(); it.pushState("off") }
                }
                RowDivider()
                ListRow(
                    title = "Bebas hemat baterai",
                    subtitle = "Menjaga koneksi tetap hidup di latar belakang",
                    trailing = {
                        if (battery) Text("Aktif", style = OrbitText.Button, color = Orbit.Ok)
                        else TextAction("Izinkan", { open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true) })
                    },
                )
                if (!listener || !featuresOk) {
                    RowDivider()
                    ListRow("Info app", "Pengaturan sistem untuk Orbit Link", onClick = {
                        open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                    })
                }
            }

            // --- aktivitas ---
            if (ui.log.isNotEmpty()) {
                Section("Aktivitas") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in ui.log.take(8)) {
                            Text(l.text, style = OrbitText.Caption, color = if (l.err) Orbit.Danger else Orbit.TextDim)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Orbit.Accent,
    unfocusedBorderColor = Orbit.Line,
    focusedLabelColor = Orbit.Accent,
    unfocusedLabelColor = Orbit.TextDim,
    cursorColor = Orbit.Accent,
    focusedTextColor = Orbit.Text,
    unfocusedTextColor = Orbit.Text,
)

/** Baris izin: belum diizinkan = aksi "Izinkan". Sudah diizinkan = toggle nyala/mati (izin Android tetap aktif). */
@Composable
private fun AllowRow(title: String, subtitle: String, granted: Boolean, key: String, onAsk: () -> Unit, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.allow(ctx, key)) }
    ListRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            if (!granted) TextAction("Izinkan", onAsk)
            else OrbitSwitch(on) { on = it; Prefs.setAllow(ctx, key, it); onToggle(it) }
        },
    )
}
