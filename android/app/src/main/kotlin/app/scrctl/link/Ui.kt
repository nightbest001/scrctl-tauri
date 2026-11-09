package app.scrctl.link

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Token desain Orbit Link: satu warna aksen, permukaan netral, jarak kelipatan 4dp. */
object Orbit {
    val Bg = Color(0xFF0E0F12)
    val Surface = Color(0xFF16171B)
    val Raised = Color(0xFF1E2025)
    val Line = Color(0xFF272A31)
    val Text = Color(0xFFF0F1F3)
    val TextDim = Color(0xFF9094A0)
    val Accent = Color(0xFF4F76FF)
    val Ok = Color(0xFF34B27F)
    val Warn = Color(0xFFD9A441)
    val Danger = Color(0xFFE0585F)
}

object OrbitText {
    val Title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Orbit.Text)
    val Heading = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Orbit.Text)
    val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = Orbit.Text)
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Orbit.TextDim, lineHeight = 17.sp)
    val Label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Orbit.TextDim, letterSpacing = 0.8.sp)
    val Button = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orbit.Accent, onPrimary = Color.White,
            background = Orbit.Bg, onBackground = Orbit.Text,
            surface = Orbit.Surface, onSurface = Orbit.Text,
            surfaceVariant = Orbit.Raised, outline = Orbit.Line,
        ),
        content = content,
    )
}

/** Kelompok baris dengan judul kecil di atasnya. Baris dipisah garis tipis lewat [RowDivider]. */
@Composable
fun Section(title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(title.uppercase(), style = OrbitText.Label, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Orbit.Surface)
                .border(1.dp, Orbit.Line, RoundedCornerShape(12.dp)),
            content = content,
        )
        if (note != null) {
            Text(note, style = OrbitText.Caption, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
        }
    }
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Orbit.Line))
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null && enabled) base.clickable(onClick = onClick) else base
    Row(
        modifier = clickable.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OrbitText.Body, color = if (enabled) Orbit.Text else Orbit.TextDim)
            if (subtitle != null) Text(subtitle, style = OrbitText.Caption, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Orbit.Accent, contentColor = Color.White,
            disabledContainerColor = Orbit.Raised, disabledContentColor = Orbit.TextDim,
        ),
    ) { Text(text, style = OrbitText.Button) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Orbit.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orbit.Text, disabledContentColor = Orbit.TextDim),
    ) { Text(text, style = OrbitText.Button) }
}

@Composable
fun OrbitSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Orbit.Accent,
            uncheckedThumbColor = Orbit.TextDim, uncheckedTrackColor = Orbit.Raised, uncheckedBorderColor = Orbit.Line,
        ),
    )
}

/** Pilihan satu dari beberapa opsi, mis. Touchpad / Layar PC. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Orbit.Raised)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Orbit.Accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = OrbitText.Button, color = if (on) Color.White else Orbit.TextDim)
            }
        }
    }
}

/** Tombol kecil untuk satu tombol keyboard atau pintasan. */
@Composable
fun KeyButton(label: String, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Orbit.Accent else Orbit.Raised)
            .border(1.dp, if (active) Orbit.Accent else Orbit.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OrbitText.Button, color = if (active) Color.White else Orbit.Text, maxLines = 1)
    }
}

/** Aksi teks kecil di ujung baris, mis. "Izinkan" atau "Buka". */
@Composable
fun TextAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = OrbitText.Button, color = if (enabled) Orbit.Accent else Orbit.TextDim)
    }
}
