package com.sophia.signal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.export.DebriefEngine
import com.sophia.signal.model.Category
import com.sophia.signal.model.Radio
import com.sophia.signal.scan.EngineState

@Composable
fun CommandScreen(onOpen: (String) -> Unit, goTab: (Int) -> Unit) {
    val app = sophia()
    val gate = LocalPermGate.current
    val live by app.repo.live.collectAsStateWithLifecycle()
    val wifi by app.scanner.wifiState.collectAsStateWithLifecycle()
    val ble by app.scanner.bleState.collectAsStateWithLifecycle()
    val session by app.repo.session.collectAsStateWithLifecycle()
    val events by app.repo.events.collectAsStateWithLifecycle()
    val now = rememberNow()
    val obs = live.values.toList()
    val active60 = obs.count { now - it.lastSeen < 60_000 }
    var sel by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel("Operations") {
            EngineLine("WI-FI", wifi, now)
            Spacer(Modifier.height(6.dp))
            EngineLine("BLE", ble, now)
            Spacer(Modifier.height(6.dp))
            Text(
                session?.let { "SESSION #${it.id} · started ${fmtTime(it.started)} · ${ago(it.started, now)} elapsed" } ?: "NO ACTIVE SESSION",
                style = MonoSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("START ALL", enabled = !(wifi.running && ble.running)) { gate { app.scanner.startAll() } }
                ActionButton("STOP ALL", color = C.Red, enabled = wifi.running || ble.running) { app.scanner.stopAll() }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Observed", obs.size.toString(), C.Green, Modifier.weight(1f))
            StatTile("Wi-Fi", obs.count { it.radio == Radio.WIFI }.toString(), C.Cyan, Modifier.weight(1f))
            StatTile("BLE", obs.count { it.radio == Radio.BLE }.toString(), C.Violet, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Priority", obs.count { it.isPriority }.toString(), C.Red, Modifier.weight(1f).clickable { goTab(4) })
            StatTile("New", obs.count { !it.seenBefore }.toString(), C.Amber, Modifier.weight(1f))
            StatTile("Returning", obs.count { it.seenBefore }.toString(), C.Text, Modifier.weight(1f))
        }

        Panel("Live radar", trailing = { Text("FULL ›", color = C.Cyan, fontSize = 11.sp, modifier = Modifier.clickable { goTab(1) }) }) {
            RadarView(obs.filter { now - it.lastSeen < 120_000 }, sel, { k -> sel = k; k?.let(onOpen) })
            Text("Radius = RSSI · angle is not a bearing", style = MonoSmall)
        }

        Panel("Signal density", accent = C.Cyan) {
            val buckets = IntArray(4).also { a -> obs.forEach { a[DebriefEngine.bucketOf(it.rssi)]++ } }
            val colors = listOf(C.Green, C.Cyan, C.Amber, C.Red)
            DebriefEngine.BUCKETS.forEachIndexed { i, label -> MeterRow(label, buckets[i], obs.size, colors[i]) }
            Spacer(Modifier.height(4.dp))
            Text("Active in last 60 s: $active60 · last 5 min: ${obs.count { now - it.lastSeen < 300_000 }}", style = MonoSmall)
        }

        Panel("Hardware classes", accent = C.Amber) {
            val counts = obs.groupingBy { it.cls.category }.eachCount()
            val mx = counts.values.maxOrNull() ?: 0
            Category.entries.forEach { c -> counts[c]?.let { MeterRow(c.label, it, mx, catColor(c)) } }
            if (counts.isEmpty()) Text("Nothing classified yet.", style = MonoSmall)
        }

        Panel("Top manufacturers", accent = C.Violet) {
            val v = obs.mapNotNull { it.cls.vendor }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.take(8)
            val mx = v.firstOrNull()?.second ?: 0
            v.forEach { MeterRow(it.first, it.second, mx, C.Violet) }
            val unid = obs.count { it.cls.vendor == null }
            if (unid > 0) MeterRow("Unidentified", unid, maxOf(mx, unid), C.Dim)
            if (obs.isEmpty()) Text("No data.", style = MonoSmall)
        }

        Panel("Priority feed", accent = C.Red) {
            if (events.isEmpty()) Text("No priority or watchlist events this run.", style = MonoSmall)
            events.take(10).forEach { e ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(e.key) }.padding(vertical = 4.dp)) {
                    Text("${fmtTime(e.ts)}  ${e.title}", color = if (e.watch) C.Red else C.Amber, fontSize = 12.sp)
                    Text(e.detail, style = MonoSmall)
                }
            }
        }
    }
}

@Composable
private fun EngineLine(name: String, s: EngineState, now: Long) {
    val color: Color = when {
        s.error != null -> C.Red
        s.running -> C.Green
        else -> C.Dim
    }
    Column {
        Text("$name  ${if (s.running) "● SCANNING" else "○ OFF"}", color = color, fontSize = 13.sp)
        Text(s.status + if (s.lastResultAt > 0) " · last data ${ago(s.lastResultAt, now)} ago (${s.lastBatch})" else "", style = MonoSmall)
        if (name == "WI-FI" && s.requested > 0) Text("scan requests ${s.requested} · throttled ${s.rejected} · visible in OS cache ${s.visible}", style = MonoSmall)
        s.error?.let { Text("⚠ $it", color = C.Red, fontSize = 11.sp) }
    }
}
