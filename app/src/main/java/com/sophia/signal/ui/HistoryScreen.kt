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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.model.ObsRow
import com.sophia.signal.model.Radio
import com.sophia.signal.model.Session
import com.sophia.signal.model.SessionCompare
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen() {
    val app = sophia()
    val version by app.repo.version.collectAsStateWithLifecycle()
    val active by app.repo.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) }
    var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var sort by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var compare by remember { mutableStateOf<SessionCompare?>(null) }
    var viewing by remember { mutableStateOf<Session?>(null) }
    var confirmDelete by remember { mutableStateOf<Session?>(null) }

    var q by remember { mutableStateOf("") }
    var radio by remember { mutableStateOf<Radio?>(null) }
    var prioOnly by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ObsRow>>(emptyList()) }

    LaunchedEffect(version) { sessions = app.repo.sessions() }
    LaunchedEffect(q, radio, prioOnly, mode, version) {
        if (mode == 1) { delay(250); results = if (q.isBlank() && !prioOnly) emptyList() else app.repo.search(q, radio, prioOnly) }
    }
    val sessionById = sessions.associateBy { it.id }
    val sorted = when (sort) {
        1 -> sessions.sortedBy { it.started }
        2 -> sessions.sortedByDescending { it.total }
        else -> sessions.sortedByDescending { it.started }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelChip("SESSIONS", mode == 0) { mode = 0 }
                SelChip("SEARCH ALL", mode == 1) { mode = 1 }
            }
        }
        if (mode == 0) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelChip("NEWEST", sort == 0) { sort = 0 }
                    SelChip("OLDEST", sort == 1) { sort = 1 }
                    SelChip("MOST SIGNALS", sort == 2) { sort = 2 }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Select two sessions to compare (${selected.size}/2)", style = MonoSmall, modifier = Modifier.weight(1f))
                    ActionButton("COMPARE", enabled = selected.size == 2) {
                        val a = sessionById[selected[0]]; val b = sessionById[selected[1]]
                        if (a != null && b != null) scope.launch {
                            val (older, newer) = if (a.started <= b.started) a to b else b to a
                            compare = app.repo.compare(older, newer)
                        }
                    }
                }
            }
            if (sorted.isEmpty()) item { Text("No stored sessions yet.", style = MonoSmall, modifier = Modifier.padding(16.dp)) }
            items(sorted, key = { it.id }) { s ->
                val isSel = s.id in selected
                val shape = RoundedCornerShape(6.dp)
                Row(
                    Modifier.fillMaxWidth().background(C.Panel, shape).border(1.dp, if (isSel) C.Green else C.Line, shape)
                        .clickable { selected = if (isSel) selected - s.id else (selected + s.id).takeLast(2) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = isSel, onCheckedChange = { selected = if (isSel) selected - s.id else (selected + s.id).takeLast(2) },
                        colors = CheckboxDefaults.colors(checkedColor = C.Green))
                    Column(Modifier.weight(1f)) {
                        Text("#${s.id}  ${fmtDate(s.started)}" + if (s.id == active?.id) "  ● ACTIVE" else "", color = C.Text, fontSize = 13.sp)
                        val dur = ((s.ended ?: System.currentTimeMillis()) - s.started) / 60_000
                        Text("Wi-Fi ${s.wifi} · BLE ${s.ble} · ${dur} min", style = MonoSmall)
                    }
                    TextButton(onClick = { viewing = s }) { Text("VIEW", color = C.Cyan, fontSize = 11.sp) }
                    TextButton(onClick = { confirmDelete = s }, enabled = s.id != active?.id) { Text("DEL", color = C.Red, fontSize = 11.sp) }
                }
            }
        } else {
            item {
                SearchField(q, { q = it }, "Search all history: name, identifier, vendor, category")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelChip("ALL", radio == null) { radio = null }
                    SelChip("WI-FI", radio == Radio.WIFI) { radio = Radio.WIFI }
                    SelChip("BLE", radio == Radio.BLE) { radio = Radio.BLE }
                    SelChip("PRIORITY", prioOnly) { prioOnly = !prioOnly }
                }
                Text("${results.size} result(s)", style = MonoSmall, modifier = Modifier.padding(top = 4.dp))
            }
            items(results, key = { "${it.sessionId}:${it.key}" }) { r -> RowLine(r, sessionById[r.sessionId]) }
        }
    }

    viewing?.let { s -> SessionDialog(s) { viewing = null } }
    compare?.let { c -> CompareDialog(c) { compare = null } }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null }, containerColor = C.Panel,
            title = { Text("Delete session #${s.id}?", color = C.Red) },
            text = { Text("Observations, RSSI samples and watch matches for this session are permanently removed.", style = MonoSmall) },
            confirmButton = { TextButton(onClick = { app.repo.deleteSession(s.id); selected = selected - s.id; confirmDelete = null }) { Text("DELETE", color = C.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("CANCEL", color = C.Dim) } },
        )
    }
}

@Composable
private fun RowLine(r: ObsRow, s: Session?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row {
            Text("[${r.radio.label}] ${r.name ?: "(no name)"}", color = C.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${r.rssiMax}", color = rssiColor(r.rssiMax), fontSize = 12.sp)
        }
        Text("${r.ident} · ${r.vendor ?: "unidentified"} · ${r.category.short} ${r.confidence}%" +
            (s?.let { " · #${it.id} ${fmtDate(it.started)}" } ?: ""), style = MonoSmall)
    }
}

@Composable
private fun SessionDialog(s: Session, onDismiss: () -> Unit) {
    val app = sophia()
    var rows by remember { mutableStateOf<List<ObsRow>>(emptyList()) }
    var f by remember { mutableStateOf("") }
    LaunchedEffect(s.id) { rows = app.repo.sessionObs(s.id) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Panel,
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = C.Green) } },
        title = { Text("Session #${s.id} · ${fmtDate(s.started)}", color = C.Green, fontSize = 15.sp) },
        text = {
            Column {
                SearchField(f, { f = it }, "Filter")
                Spacer(Modifier.height(6.dp))
                val shown = rows.filter { f.isBlank() || listOfNotNull(it.name, it.ident, it.vendor).any { x -> x.contains(f, true) } }
                Text("${shown.size} of ${rows.size}", style = MonoSmall)
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    shown.take(300).forEach { RowLine(it, null) }
                }
            }
        },
    )
}

@Composable
private fun CompareDialog(c: SessionCompare, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Panel,
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = C.Green) } },
        title = { Text("#${c.a.id} → #${c.b.id}", color = C.Green, fontSize = 15.sp) },
        text = {
            Column {
                Text("${fmtDate(c.a.started)} → ${fmtDate(c.b.started)}", style = MonoSmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelChip("NEW ${c.onlyB.size}", tab == 0) { tab = 0 }
                    SelChip("GONE ${c.onlyA.size}", tab == 1) { tab = 1 }
                    SelChip("BOTH ${c.both.size}", tab == 2) { tab = 2 }
                }
                Spacer(Modifier.height(6.dp))
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    when (tab) {
                        0 -> c.onlyB.sortedByDescending { it.rssiMax }.take(300).forEach { RowLine(it, null) }
                        1 -> c.onlyA.sortedByDescending { it.rssiMax }.take(300).forEach { RowLine(it, null) }
                        else -> c.both.sortedByDescending { kotlin.math.abs(it.second.rssiMax - it.first.rssiMax) }.take(300).forEach { (a, b) ->
                            val d = b.rssiMax - a.rssiMax
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text("${b.name ?: b.ident}", color = C.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text("${a.rssiMax}→${b.rssiMax} (${if (d >= 0) "+" else ""}$d)", color = if (d >= 0) C.Green else C.Amber, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
    )
}
