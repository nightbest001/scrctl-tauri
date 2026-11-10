package app.scrctl.link

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Menjadikan HP ini bisa dikontrol HP lain: aktifkan layanan, mulai server, tampilkan alamat dan kode. */
class HostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OrbitTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Orbit.Bg) {
                    HostScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun HostScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ui by HostHub.ui.collectAsState()
    var access by remember { mutableStateOf(RemoteInputService.instance != null) }
    var token by remember { mutableStateOf(Prefs.hostToken(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            access = RemoteInputService.instance != null
            delay(1000)
        }
    }
    val addrs = remember(ui.running) { HostCode.addresses() }

    val askCapture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val i = Intent(ctx, HostService::class.java)
                .putExtra(HostService.EXTRA_CODE, r.resultCode)
                .putExtra(HostService.EXTRA_DATA, r.data)
            ctx.startForegroundService(i)
        } else {
            Toast.makeText(ctx, "Izin tangkap layar ditolak. Layar HP ini tidak akan tampil di HP pengontrol.", Toast.LENGTH_LONG).show()
            ctx.startForegroundService(Intent(ctx, HostService::class.java).putExtra(HostService.EXTRA_CODE, 0))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Kembali", style = OrbitText.Button, color = Orbit.Accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("HP ini bisa dikontrol", style = OrbitText.Title)
            }
        }

        Section(
            "Persiapan",
            note = "Layanan Aksesibilitas dibutuhkan agar ketukan, geseran, dan ketikan dari HP lain bisa dijalankan. " +
                "Android akan menampilkan peringatan; itu normal untuk fitur ini.",
        ) {
            ListRow(
                title = "Layanan Aksesibilitas Orbit Link",
                subtitle = if (access) "Aktif" else "Belum aktif. Buka Setelan, pilih Orbit Link, lalu nyalakan.",
                trailing = {
                    if (access) StatusDot(Orbit.Ok)
                    else TextAction("Buka", {
                        try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        catch (_: Exception) { Toast.makeText(ctx, "Tidak bisa membuka pengaturan", Toast.LENGTH_SHORT).show() }
                    })
                },
            )
        }

        Section(
            "Mode dikontrol",
            note = "Kedua HP harus satu jaringan, misalnya hotspot dari salah satu HP. Tidak perlu internet.",
        ) {
            val state = when {
                ui.error.isNotEmpty() -> ui.error
                ui.client != null -> "Dikontrol oleh ${ui.client}"
                ui.running -> "Menunggu HP pengontrol"
                else -> "Mati"
            }
            ListRow(
                title = state,
                subtitle = if (ui.running && ui.screen) "Layar HP ini sedang dilihat" else null,
                trailing = { StatusDot(if (ui.client != null) Orbit.Ok else if (ui.running) Orbit.Accent else Orbit.TextDim) },
            )
            RowDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.running) {
                    Text("Isi di HP pengontrol (app Orbit Link, kolom Alamat PC)", style = OrbitText.Caption)
                    if (addrs.isEmpty()) {
                        Text("Belum ada alamat jaringan. Sambungkan ke WiFi atau nyalakan hotspot.", style = OrbitText.Body, color = Orbit.Warn)
                    } else {
                        for (a in addrs) Text(a, style = OrbitText.Heading)
                    }
                    Text("Kode", style = OrbitText.Label)
                    Text(HostCode.pretty(token), style = OrbitText.Title)
                    SecondaryButton("Kode baru", {
                        token = Prefs.regenHostToken(ctx)
                        // kode baru dipakai server berikutnya: hentikan dan mulai lagi
                        ctx.startService(Intent(ctx, HostService::class.java).setAction(HostService.ACTION_STOP))
                        Toast.makeText(ctx, "Kode baru dibuat. Tekan Mulai lagi.", Toast.LENGTH_LONG).show()
                    }, Modifier.fillMaxWidth())
                    SecondaryButton("Hentikan", {
                        ctx.startService(Intent(ctx, HostService::class.java).setAction(HostService.ACTION_STOP))
                    }, Modifier.fillMaxWidth())
                } else {
                    Text(
                        "Saat dimulai, Android meminta izin merekam layar. Izin itu dipakai hanya untuk menampilkan layar HP ini di HP pengontrol.",
                        style = OrbitText.Caption,
                    )
                    PrimaryButton("Mulai", {
                        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
                        askCapture.launch(mpm.createScreenCaptureIntent())
                    }, Modifier.fillMaxWidth())
                }
            }
        }

        Text(
            "Siapa pun yang tahu kode bisa melihat dan mengontrol HP ini selama mode menyala. " +
                "Data dienkripsi. Matikan setelah selesai, dan jangan bagikan kode. Lima kode salah mengunci server 30 detik.",
            style = OrbitText.Caption,
        )
    }
}
