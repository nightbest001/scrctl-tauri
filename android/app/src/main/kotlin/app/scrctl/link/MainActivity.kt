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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF121214)
private val CardBg = Color(0xFF1F2025)
private val Blue = Color(0xFF335FFF)
private val Green = Color(0xFF00C97F)
private val Red = Color(0xFFE8505B)
private val Dim = Color(0xFFBDBEBE)

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue, onPrimary = Color.White,
                    background = Bg, onBackground = Color.White,
                    surface = Bg, onSurface = Color.White,
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Bg) { Home() }
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Orbit Link", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)

            // --- update ---
            upd?.let { u ->
                Panel {
                    Text("Update tersedia: ${u.tag}", fontWeight = FontWeight.Bold, color = Green)
                    Text("Versi kamu ${Updater.currentVersion(ctx)}. Unduh APK baru lalu pasang (menimpa yang lama).", fontSize = 12.sp, color = Dim)
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        val url = u.apkUrl.ifEmpty { u.pageUrl }
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast("Tidak bisa membuka link") }
                    }) { Text("Download update") }
                }
            }

            // --- status ---
            Panel {
                val (dot, label) = when (ui.conn) {
                    Conn.Connected -> Green to "Terhubung ke ${ui.pc ?: "PC"}"
                    Conn.Connecting -> Blue to "Menghubungkan…"
                    Conn.Waiting -> Color(0xFFE0A030) to "Mencoba menyambung lagi…"
                    Conn.AuthFailed -> Red to "Kode ditolak"
                    Conn.Stopped -> Dim to "Tidak terhubung"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = dot) {}
                    Text(label, fontWeight = FontWeight.Bold)
                }
                if (ui.message.isNotEmpty() && ui.conn != Conn.Connected) Text(ui.message, fontSize = 12.sp, color = Dim)
            }

            // --- sambungkan ---
            Panel {
                Text("Sambungkan ke PC", fontWeight = FontWeight.Bold)
                Text("Buka Orbit di PC → menu Link HP. Isi alamat dan kode yang tampil di sana.", fontSize = 12.sp, color = Dim)
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Alamat PC (mis. 192.168.1.10)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text("Kode (8 karakter)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                val idle = ui.conn == Conn.Stopped || ui.conn == Conn.AuthFailed
                if (idle) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        val hp = parseHost(host)
                        val tk = Proto.normalizeToken(code)
                        if (hp == null || tk.length != 8) toast("Isi alamat PC dan kode 8 karakter")
                        else connect(hp.first, hp.second, tk)
                    }) { Text("Hubungkan") }
                } else {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { disconnect() }) { Text("Putuskan") }
                }
            }

            // --- kirim ke PC ---
            Panel {
                Text("Kirim ke PC", fontWeight = FontWeight.Bold)
                val on = ui.conn == Conn.Connected
                Button(modifier = Modifier.fillMaxWidth(), enabled = on, onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Text("Kirim file")
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = on, onClick = { sendClipboard() }) {
                    Text("Kirim clipboard HP")
                }
                Text(
                    "Atau pakai menu Bagikan → Orbit Link dari app mana pun. Clipboard PC → HP tersinkron otomatis. " +
                        "HP → PC harus ditekan manual: Android melarang app di background membaca clipboard.",
                    fontSize = 12.sp, color = Dim,
                )
            }

            // --- izin ---
            Panel {
                Text("Izin", fontWeight = FontWeight.Bold)
                @Suppress("UNUSED_VARIABLE")
                val tick = resumeTick.intValue // dibaca di sini supaya status izin dihitung ulang tiap kembali ke app
                val listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
                val nm = ctx.getSystemService(NotificationManager::class.java)
                val dnd = nm.isNotificationPolicyAccessGranted
                val battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
                val postOk = Build.VERSION.SDK_INT < 33 ||
                    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

                PermRow("Akses notifikasi (kirim notifikasi HP ke PC)", listener) {
                    open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                if (!listener) {
                    Text(
                        "Android 13+: kalau pilihannya abu-abu, buka Info app → ⋮ (pojok kanan atas) → Izinkan setelan terbatas, lalu ulangi.",
                        fontSize = 12.sp, color = Dim,
                    )
                    TextButton(onClick = { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("Buka Info app") }
                }
                PermRow("Jangan Ganggu (dikontrol dari PC)", dnd) {
                    open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                }
                PermRow("Notifikasi app (status koneksi)", postOk) {
                    if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val featuresOk = featurePerms().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                PermRow("Pesan, kontak, telepon, foto (halaman Pesan/Telepon/Foto di PC)", featuresOk) {
                    askFeatures.launch(featurePerms())
                }
                if (!featuresOk) {
                    Text(
                        "Kalau dialog izin tidak muncul (Android 13+, app dari luar Play Store): Info app → ⋮ → Izinkan setelan terbatas, lalu ulangi.",
                        fontSize = 12.sp, color = Dim,
                    )
                }
                val camOk = ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                PermRow("Kamera (buka kamera HP dari PC)", camOk) { askCamera.launch(Manifest.permission.CAMERA) }
                PermRow("Bebas hemat baterai (koneksi tidak diputus)", battery) {
                    open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true)
                }
            }

            // --- log ---
            if (ui.log.isNotEmpty()) {
                Panel {
                    Text("Aktivitas", fontWeight = FontWeight.Bold)
                    for (l in ui.log.take(8)) {
                        Text(l.text, fontSize = 12.sp, color = if (l.err) Red else Dim)
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        if (ok) {
            Text("Aktif", color = Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        } else {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
            ) { Text("Izinkan") }
        }
    }
}
