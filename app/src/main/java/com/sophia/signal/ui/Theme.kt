@file:OptIn(ExperimentalMaterial3Api::class)

package com.sophia.signal.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sophia.signal.SophiaApp
import com.sophia.signal.model.Category
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object C {
    val Bg = Color(0xFF050807)
    val Panel = Color(0xFF0B1210)
    val Panel2 = Color(0xFF111B18)
    val Line = Color(0xFF1E2D28)
    val Green = Color(0xFF39FF88)
    val Cyan = Color(0xFF22D3EE)
    val Amber = Color(0xFFFFB020)
    val Red = Color(0xFFFF4D5E)
    val Violet = Color(0xFFB18CFF)
    val Text = Color(0xFFCFE8DC)
    val Dim = Color(0xFF6B8579)
}

fun catColor(c: Category): Color = when (c) {
    Category.CELLULAR -> C.Amber
    Category.ROUTER -> C.Green
    Category.INFRASTRUCTURE -> C.Cyan
    Category.COMMERCIAL -> Color(0xFF7FE3B0)
    Category.BT_ACCESSORY -> C.Violet
    Category.TRACKER -> C.Red
    Category.BEACON -> Color(0xFF5EA8FF)
    Category.UNKNOWN -> C.Dim
}

fun rssiColor(r: Int): Color = when {
    r >= -55 -> C.Green
    r >= -70 -> C.Cyan
    r >= -82 -> C.Amber
    else -> C.Red
}

private val Mono = FontFamily.Monospace

@Composable
fun SophiaTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Green, onPrimary = Color.Black, secondary = C.Cyan, onSecondary = Color.Black,
            background = C.Bg, onBackground = C.Text, surface = C.Panel, onSurface = C.Text,
            surfaceVariant = C.Panel2, onSurfaceVariant = C.Dim, error = C.Red, outline = C.Line,
            surfaceContainer = C.Panel, surfaceContainerHigh = C.Panel2, surfaceContainerHighest = C.Panel2,
        ),
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = Mono),
            bodyMedium = base.bodyMedium.copy(fontFamily = Mono),
            bodySmall = base.bodySmall.copy(fontFamily = Mono),
            titleLarge = base.titleLarge.copy(fontFamily = Mono),
            titleMedium = base.titleMedium.copy(fontFamily = Mono),
            titleSmall = base.titleSmall.copy(fontFamily = Mono),
            labelLarge = base.labelLarge.copy(fontFamily = Mono),
            labelMedium = base.labelMedium.copy(fontFamily = Mono),
            labelSmall = base.labelSmall.copy(fontFamily = Mono),
        ),
        content = content,
    )
}

@Composable
fun sophia(): SophiaApp = LocalContext.current.applicationContext as SophiaApp

val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = C.Dim)

@Composable
fun Panel(
    title: String? = null,
    modifier: Modifier = Modifier,
    accent: Color = C.Green,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier.fillMaxWidth().background(C.Panel, shape).border(1.dp, C.Line, shape).padding(12.dp)
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(title.uppercase(), color = accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Column(modifier.background(C.Panel, shape).border(1.dp, color.copy(alpha = 0.35f), shape).padding(10.dp)) {
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
        Text(label.uppercase(), color = C.Dim, fontSize = 10.sp, letterSpacing = 1.5.sp, fontFamily = Mono)
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 10.sp, fontFamily = Mono,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
fun RssiBar(rssi: Int, modifier: Modifier = Modifier) {
    val f = ((rssi + 100) / 70f).coerceIn(0.02f, 1f)
    Box(modifier.height(6.dp).background(C.Line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(rssiColor(rssi)))
    }
}

@Composable
fun MeterRow(label: String, value: Int, max: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = C.Text, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.weight(0.45f), maxLines = 1)
        Box(Modifier.weight(0.4f).height(6.dp).background(C.Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (max == 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)).background(color))
        }
        Text(value.toString(), color = color, fontSize = 11.sp, fontFamily = Mono, modifier = Modifier.weight(0.15f).padding(start = 6.dp))
    }
}

@Composable
fun ActionButton(text: String, color: Color = C.Green, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        border = BorderStroke(1.dp, if (enabled) color else C.Line),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = C.Dim),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text, fontSize = 12.sp, letterSpacing = 1.sp, fontFamily = Mono) }
}

@Composable
fun SelChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(text, fontSize = 11.sp, fontFamily = Mono) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = C.Panel, labelColor = C.Dim,
            selectedContainerColor = C.Green.copy(alpha = 0.15f), selectedLabelColor = C.Green,
        ),
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        placeholder = { Text(hint, color = C.Dim, fontSize = 12.sp) },
        textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = C.Text),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Green, unfocusedBorderColor = C.Line, cursorColor = C.Green),
    )
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
fun fmtTime(ms: Long): String = timeFmt.format(Date(ms))
fun fmtDate(ms: Long): String = dateFmt.format(Date(ms))

fun ago(ts: Long, now: Long): String {
    val s = ((now - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
}

@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    val v by produceState(System.currentTimeMillis()) {
        while (true) { delay(periodMs); value = System.currentTimeMillis() }
    }
    return v
}

/** Save-as flow using the Storage Access Framework (no storage permission needed). */
@Composable
fun rememberExporter(mime: String): (fileName: String, content: () -> String) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> String)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val p = pending
        pending = null
        if (uri != null && p != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(p().toByteArray(Charsets.UTF_8)) } ?: error("no stream")
            }.isSuccess
            Toast.makeText(ctx, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    return { name, producer -> pending = producer; launcher.launch(name) }
}

val LocalPermGate = staticCompositionLocalOf<(() -> Unit) -> Unit> { { it() } }
