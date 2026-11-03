@file:OptIn(ExperimentalMaterial3Api::class)

package com.sophia.signal.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.model.Category
import com.sophia.signal.model.Observation
import com.sophia.signal.model.Radio
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Angle is a stable hash of the identifier (so markers don't jump); radius is RSSI.
 * Neither represents direction or physical position — a single phone cannot measure bearing.
 */
private fun markerAngleDeg(o: Observation): Float = ((o.key.hashCode().toLong() and 0x7fffffffL) % 360L).toFloat()

private fun markerPos(o: Observation, c: Offset, r: Float): Offset {
    val a = Math.toRadians(markerAngleDeg(o).toDouble())
    val norm = ((-o.rssi - 30).coerceIn(0, 70)) / 70f
    val d = r * (0.12f + 0.86f * norm)
    return Offset(c.x + (d * cos(a)).toFloat(), c.y + (d * sin(a)).toFloat())
}

@Composable
fun RadarView(
    obs: List<Observation>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tr = rememberInfiniteTransition(label = "sweep")
    val sweep by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "angle")
    val now = rememberNow()
    val current by rememberUpdatedState(obs)

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = min(size.width, size.height) / 2f * 0.94f
                    val best = current.minByOrNull { (markerPos(it, c, r) - p).getDistance() }
                    if (best != null && (markerPos(best, c, r) - p).getDistance() < 48f) onSelect(best.key) else onSelect(null)
                }
            }
    ) {
        val c = center
        val r = size.minDimension / 2f * 0.94f
        val ring = C.Green.copy(alpha = 0.18f)
        for (i in 1..4) drawCircle(ring, r * i / 4f, c, style = Stroke(1.dp.toPx()))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())

        rotate(sweep, pivot = c) {
            drawArc(
                brush = Brush.sweepGradient(0f to Color.Transparent, 0.885f to Color.Transparent, 1f to C.Green.copy(alpha = 0.35f), center = c),
                startAngle = -42f, sweepAngle = 42f, useCenter = true,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
            )
            drawLine(C.Green.copy(alpha = 0.9f), c, Offset(c.x + r, c.y), 2.dp.toPx())
        }

        for (o in obs) {
            val p = markerPos(o, c, r)
            val age = (now - o.lastSeen).coerceAtLeast(0)
            val fresh = (1f - age / 120_000f).coerceIn(0.18f, 1f)
            val diff = ((sweep - markerAngleDeg(o)) % 360f + 360f) % 360f
            val glow = if (diff < 45f) 1f - diff / 45f else 0f
            val color = catColor(o.cls.category)
            val base = (3f + ((o.rssi + 100).coerceIn(0, 70) / 70f) * 5f).dp.toPx()
            val alpha = (fresh * (0.55f + 0.45f * glow)).coerceIn(0.15f, 1f)
            if (glow > 0f) drawCircle(color.copy(alpha = 0.25f * glow * fresh), base * 2.4f, p)
            if (o.radio == Radio.WIFI) drawCircle(color.copy(alpha = alpha), base, p)
            else rotate(45f, pivot = p) {
                drawRect(color.copy(alpha = alpha), Offset(p.x - base, p.y - base), Size(base * 2, base * 2))
            }
            if (o.isPriority) drawCircle(C.Red.copy(alpha = 0.9f), base + 4.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            if (o.key == selected) drawCircle(Color.White, base + 7.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
        drawCircle(C.Green, 3.dp.toPx(), c)
    }
}

@Composable
fun RadarScreen(onOpen: (String) -> Unit) {
    val app = sophia()
    val live by app.repo.live.collectAsStateWithLifecycle()
    var showWifi by remember { mutableStateOf(true) }
    var showBle by remember { mutableStateOf(true) }
    var priorityOnly by remember { mutableStateOf(false) }
    var recentOnly by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf<String?>(null) }
    val now = rememberNow(2000)
    val obs = live.values.filter {
        (showWifi || it.radio != Radio.WIFI) && (showBle || it.radio != Radio.BLE) &&
            (!priorityOnly || it.isPriority) && (!recentOnly || now - it.lastSeen < 120_000)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelChip("WI-FI", showWifi) { showWifi = !showWifi }
            SelChip("BLE", showBle) { showBle = !showBle }
            SelChip("PRIORITY", priorityOnly) { priorityOnly = !priorityOnly }
            SelChip("<2 MIN", recentOnly) { recentOnly = !recentOnly }
        }
        Panel("Signal radar · ${obs.size} markers") {
            RadarView(obs, sel, { sel = it })
            Spacer(Modifier.height(8.dp))
            Text(
                "Ring distance = signal strength (center ≈ -30 dBm, edge ≈ -100 dBm). Angle is a fixed per-identifier " +
                    "position so markers stay put — it is NOT a direction or location. ● Wi-Fi  ◆ BLE  red ring = priority.",
                style = MonoSmall,
            )
        }
        val o = sel?.let { live[it] }
        if (o != null) {
            Panel("Selected", accent = catColor(o.cls.category)) {
                Text(o.label ?: o.name ?: "(no name)", color = C.Text, fontSize = 15.sp)
                Text(o.id, style = MonoSmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RssiBar(o.rssi, Modifier.weight(1f)); Spacer(Modifier.width(8.dp))
                    Text("${o.rssi} dBm", color = rssiColor(o.rssi), fontSize = 12.sp)
                }
                Text("${o.cls.category.label} · ${o.cls.confidence}% · ${o.cls.vendor ?: "vendor unknown"}", color = C.Dim, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                ActionButton("OPEN DETAILS") { onOpen(o.key) }
            }
        }
        Panel("Legend") {
            Category.entries.forEach { c ->
                val n = obs.count { it.cls.category == c }
                if (n > 0) MeterRow(c.label, n, obs.size, catColor(c))
            }
            if (obs.isEmpty()) Text("No signals to plot. Start Wi-Fi or BLE scanning from COMMAND.", style = MonoSmall)
        }
    }
}
