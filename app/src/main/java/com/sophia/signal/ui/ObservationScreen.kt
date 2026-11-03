package com.sophia.signal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.data.Repository
import com.sophia.signal.export.Exporter
import com.sophia.signal.model.Observation
import com.sophia.signal.model.Radio

private val SORTS = listOf("RSSI", "NAME", "LAST SEEN", "FIRST SEEN", "SIGHTINGS")

@Composable
fun ObservationScreen(radio: Radio, onOpen: (String) -> Unit) {
    val app = sophia()
    val gate = LocalPermGate.current
    val live by app.repo.live.collectAsStateWithLifecycle()
    val session by app.repo.session.collectAsStateWithLifecycle()
    val state by (if (radio == Radio.WIFI) app.scanner.wifiState else app.scanner.bleState).collectAsStateWithLifecycle()
    var q by rememberSaveable(radio) { mutableStateOf("") }
    var sort by rememberSaveable(radio) { mutableIntStateOf(0) }
    var onlyPriority by rememberSaveable(radio) { mutableStateOf(false) }
    var onlyNew by rememberSaveable(radio) { mutableStateOf(false) }
    val now = rememberNow()
    val export = rememberExporter("text/csv")

    val all = live.values.filter { it.radio == radio }
    val items = remember(live, q, sort, onlyPriority, onlyNew, radio) {
        all.asSequence()
            .filter { !onlyPriority || it.isPriority }
            .filter { !onlyNew || !it.seenBefore }
            .filter {
                q.isBlank() || listOfNotNull(it.name, it.id, it.cls.vendor, it.label, it.cls.category.label)
                    .any { s -> s.contains(q, ignoreCase = true) }
            }
            .sortedWith(
                when (sort) {
                    1 -> compareBy<Observation> { (it.label ?: it.name ?: "zzzz").lowercase() }
                    2 -> compareByDescending<Observation> { it.lastSeen }
                    3 -> compareBy<Observation> { it.firstSeen }
                    4 -> compareByDescending<Observation> { it.count }
                    else -> compareByDescending<Observation> { it.rssi }
                }
            ).toList()
    }
    val fpCounts = if (radio == Radio.BLE) all.filter { it.raw.fingerprint.length > 3 }.groupingBy { it.raw.fingerprint }.eachCount() else emptyMap()

    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Panel("${radio.label} observation engine") {
                Text(
                    (if (state.running) "● SCANNING  " else "○ OFF  ") + state.status,
                    color = if (state.running) C.Green else C.Dim, fontSize = 12.sp,
                )
                if (radio == Radio.WIFI && state.requested > 0)
                    Text("requests ${state.requested} · throttled ${state.rejected} · interval ${app.prefs.settings.value.wifiIntervalSec}s", style = MonoSmall)
                state.error?.let { Text("⚠ $it", color = C.Red, fontSize = 11.sp) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.running) ActionButton("STOP", color = C.Red) {
                        if (radio == Radio.WIFI) app.scanner.stopWifi() else app.scanner.stopBle()
                    } else ActionButton("START") {
                        gate { if (radio == Radio.WIFI) app.scanner.startWifi() else app.scanner.startBle() }
                    }
                    ActionButton("CLEAR", color = C.Amber, enabled = all.isNotEmpty()) { app.repo.clearLive(radio) }
                    ActionButton("EXPORT CSV", color = C.Cyan, enabled = all.isNotEmpty()) {
                        val sid = session?.id ?: 0L
                        val rows = all.map { Repository.toRow(sid, it) }
                        export("sophia_${radio.name.lowercase()}_${System.currentTimeMillis() / 1000}.csv") { Exporter.csv(rows) }
                    }
                }
                if (radio == Radio.BLE) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Many phones, watches and earbuds rotate random BLE addresses (~15 min). One device may appear as several entries; " +
                            "\"≈N\" marks entries whose advertisement payload looks identical.",
                        style = MonoSmall,
                    )
                }
                if (radio == Radio.WIFI) {
                    Spacer(Modifier.height(6.dp))
                    Text("Android allows 4 scans per 2 minutes in the foreground. Clear only hides live entries; history is kept.", style = MonoSmall)
                }
            }
        }
        item {
            SearchField(q, { q = it }, "Search name, identifier, vendor, label…")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SORTS.forEachIndexed { i, s -> SelChip(s, sort == i) { sort = i } }
                SelChip("PRIORITY", onlyPriority) { onlyPriority = !onlyPriority }
                SelChip("NEW ONLY", onlyNew) { onlyNew = !onlyNew }
            }
            Text("${items.size} of ${all.size} shown", style = MonoSmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (items.isEmpty()) item {
            Text(
                if (all.isEmpty()) "No ${radio.label} observations yet. Press START. Nothing is ever simulated." else "No matches.",
                style = MonoSmall, modifier = Modifier.padding(16.dp),
            )
        }
        items(items, key = { it.key }) { o ->
            ObsCard(o, now, fpCounts[o.raw.fingerprint] ?: 0) { onOpen(o.key) }
        }
    }
}

@Composable
fun ObsCard(o: Observation, now: Long, similar: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    val stale = now - o.lastSeen > 120_000
    Column(
        Modifier.fillMaxWidth().background(C.Panel, shape)
            .border(1.dp, if (o.isPriority) C.Red.copy(alpha = 0.6f) else C.Line, shape)
            .clickable(onClick = onClick).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                o.label ?: o.name ?: if (o.radio == Radio.WIFI) "<hidden SSID>" else "(no name)",
                color = if (stale) C.Dim else C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("${o.rssi}", color = rssiColor(o.rssi), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                when { o.trend >= 3 -> " ▲"; o.trend <= -3 -> " ▼"; else -> " ·" },
                color = when { o.trend >= 3 -> C.Green; o.trend <= -3 -> C.Amber; else -> C.Dim }, fontSize = 12.sp,
            )
        }
        Text(o.id + (o.cls.vendor?.let { " · $it" } ?: ""), style = MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        RssiBar(o.rssi, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        val r = o.raw
        Text(
            if (o.radio == Radio.WIFI) listOfNotNull(r.band, r.channel?.let { "ch $it" }, r.frequency?.let { "$it MHz" }, r.security).joinToString(" · ")
            else listOfNotNull(
                r.companyIds.firstOrNull()?.let { "co 0x%04X".format(it) },
                r.serviceUuids.size.takeIf { it > 0 }?.let { "$it svc" },
                r.txPower?.let { "tx $it" },
                r.connectable?.let { if (it) "connectable" else "non-conn" },
            ).joinToString(" · ").ifEmpty { "no payload metadata" },
            style = MonoSmall,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag("${o.cls.category.short} ${o.cls.confidence}%", catColor(o.cls.category))
            if (o.seenBefore) Tag("SEEN BEFORE ×${o.priorSessions}", C.Cyan) else Tag("NEW", C.Amber)
            if (o.cls.priority) Tag("PRIORITY", C.Red)
            if (o.watchHit != null) Tag("WATCH", C.Red)
            if (o.flagged) Tag("FLAG", C.Amber)
            if (similar > 1) Tag("≈$similar", C.Violet)
            Text("  ${o.count}× · ${ago(o.lastSeen, now)} ago", style = MonoSmall)
        }
    }
}
