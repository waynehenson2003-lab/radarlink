package com.sophia.signal.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.export.Debrief
import com.sophia.signal.export.DebriefEngine
import com.sophia.signal.export.Exporter
import com.sophia.signal.model.Session

@Composable
fun DebriefScreen() {
    val app = sophia()
    val version by app.repo.version.collectAsStateWithLifecycle()
    val active by app.repo.session.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var chosen by remember { mutableStateOf<Long?>(null) }
    var debrief by remember { mutableStateOf<Debrief?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val exportCsv = rememberExporter("text/csv")
    val exportJson = rememberExporter("application/json")
    val exportTxt = rememberExporter("text/markdown")

    LaunchedEffect(version) {
        sessions = app.repo.sessions()
        if (chosen == null || sessions.none { it.id == chosen }) chosen = active?.id ?: sessions.firstOrNull()?.id
    }
    LaunchedEffect(chosen, refresh) {
        val s = sessions.firstOrNull { it.id == chosen } ?: run { debrief = null; return@LaunchedEffect }
        busy = true
        debrief = DebriefEngine.build(app.repo, s)
        busy = false
    }
    val text = remember(debrief) { debrief?.let { Exporter.aiDebrief(it) } ?: "" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel("Session") {
            if (sessions.isEmpty()) Text("No sessions recorded yet. Run a scan first.", style = MonoSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sessions.take(20).forEach { s ->
                    SelChip("#${s.id} ${fmtDate(s.started).substring(5)}${if (s.id == active?.id) " ●" else ""}", chosen == s.id) { chosen = s.id }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(if (busy) "BUILDING…" else "REGENERATE", enabled = !busy && chosen != null) { refresh++ }
            }
            Text("All reports are generated on this device. Nothing is uploaded.", style = MonoSmall)
        }
        val d = debrief
        if (d != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Wi-Fi", d.wifi.toString(), C.Cyan, Modifier.weight(1f))
                StatTile("BLE", d.ble.toString(), C.Violet, Modifier.weight(1f))
                StatTile("Priority", d.priority.size.toString(), C.Red, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("New", d.newIds.size.toString(), C.Amber, Modifier.weight(1f))
                StatTile("Returning", d.returning.size.toString(), C.Text, Modifier.weight(1f))
                StatTile("Unknown", d.unknown.size.toString(), C.Dim, Modifier.weight(1f))
            }
            Panel("Export", accent = C.Cyan) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val stamp = "sophia_session${d.session.id}"
                    ActionButton("CSV", color = C.Cyan) { exportCsv("$stamp.csv") { Exporter.csv(d.rows) } }
                    ActionButton("JSON", color = C.Cyan) { exportJson("$stamp.json") { Exporter.json(d) } }
                    ActionButton("AI DEBRIEF", color = C.Cyan) { exportTxt("${stamp}_debrief.md") { text } }
                }
            }
            Panel("AI-readable debrief", accent = C.Green) {
                SelectionContainer { Text(text, color = C.Text, fontSize = 11.sp, lineHeight = 15.sp) }
            }
        }
    }
}
