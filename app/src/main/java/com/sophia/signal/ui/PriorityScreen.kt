package com.sophia.signal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.model.HitRow
import com.sophia.signal.model.WatchType

@Composable
fun PriorityScreen(onOpen: (String) -> Unit) {
    val app = sophia()
    val live by app.repo.live.collectAsStateWithLifecycle()
    val watch by app.repo.watch.collectAsStateWithLifecycle()
    val flagged by app.repo.flagged.collectAsStateWithLifecycle()
    val events by app.repo.events.collectAsStateWithLifecycle()
    val version by app.repo.version.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<HitRow>>(emptyList()) }
    val now = rememberNow()
    LaunchedEffect(version, events.size) { hits = app.repo.hits(limit = 50) }
    val livePriority = live.values.filter { it.isPriority }.sortedByDescending { it.rssi }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel("Live priority now · ${livePriority.size}", accent = C.Red) {
                if (livePriority.isEmpty()) Text("No priority, flagged or watched signals in the current view.", style = MonoSmall)
                livePriority.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(o.key) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.label ?: o.name ?: o.id, color = C.Text, fontSize = 13.sp)
                            Text(listOfNotNull(o.watchHit, o.cls.priorityReason, if (o.flagged) "flagged" else null).joinToString(" · "), style = MonoSmall)
                        }
                        Text("${o.rssi}", color = rssiColor(o.rssi), fontSize = 14.sp)
                    }
                }
            }
        }
        item {
            Panel("Watchlists · ${watch.size}", accent = C.Red, trailing = {
                Text("+ ADD", color = C.Green, fontSize = 12.sp, modifier = Modifier.clickable { adding = true })
            }) {
                Text("You are alerted when a match first appears in a session, or reappears after the cooldown (${app.prefs.settings.value.cooldownMin} min). Values are encrypted on device.", style = MonoSmall)
                Spacer(Modifier.height(6.dp))
                if (watch.isEmpty()) Text("No watch rules yet.", style = MonoSmall)
                watch.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(w.label ?: w.value, color = if (w.enabled) C.Text else C.Dim, fontSize = 13.sp)
                            Text("${w.type.name}: ${w.value}", style = MonoSmall)
                        }
                        Switch(checked = w.enabled, onCheckedChange = { app.repo.setWatchEnabled(w.id, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = C.Green, checkedTrackColor = C.Green.copy(alpha = 0.3f)))
                        TextButton(onClick = { app.repo.deleteWatch(w.id) }) { Text("DEL", color = C.Red, fontSize = 11.sp) }
                    }
                }
            }
        }
        item {
            Panel("Flagged identifiers · ${flagged.size}", accent = C.Amber) {
                if (flagged.isEmpty()) Text("Flag an observation from its detail view to track it here.", style = MonoSmall)
                flagged.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onOpen(f.key) }.padding(vertical = 3.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label ?: f.ident, color = C.Text, fontSize = 13.sp)
                            Text("${f.radio.label} ${f.ident} · ${f.sessions} session(s) · last ${ago(f.lastSeen, now)} ago" +
                                if (live.containsKey(f.key)) " · LIVE" else "", style = MonoSmall)
                        }
                        TextButton(onClick = { app.repo.setFlag(f.key, false) }) { Text("UNFLAG", color = C.Amber, fontSize = 11.sp) }
                    }
                }
            }
        }
        item {
            Panel("Recent watch matches (stored)", accent = C.Cyan) {
                if (hits.isEmpty()) Text("None recorded.", style = MonoSmall)
                hits.take(20).forEach { h -> Text("${fmtDate(h.ts)}  ${h.key.substringAfter(':')}  ${h.rssi} dBm  ${h.note ?: ""}", style = MonoSmall) }
            }
        }
        item {
            Panel("Event log (this run)", accent = C.Dim) {
                if (events.isEmpty()) Text("No events.", style = MonoSmall)
            }
        }
        items(events.take(60), key = { "${it.ts}-${it.key}-${it.title}" }) { e ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(e.key) }.padding(horizontal = 4.dp)) {
                Text("${fmtTime(e.ts)}  ${e.title}", color = if (e.watch) C.Red else C.Amber, fontSize = 12.sp)
                Text(e.detail, style = MonoSmall)
            }
        }
    }

    if (adding) AddWatchDialog(onDismiss = { adding = false }) { t, v, l -> app.repo.addWatch(t, v, l); adding = false }
}

@Composable
private fun AddWatchDialog(onDismiss: () -> Unit, onAdd: (WatchType, String, String?) -> Unit) {
    var type by remember { mutableStateOf(WatchType.EXACT_ID) }
    var value by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Panel,
        title = { Text("New watch rule", color = C.Green) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WatchType.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { type = t }) {
                        RadioButton(selected = type == t, onClick = { type = t }, colors = RadioButtonDefaults.colors(selectedColor = C.Green))
                        Text(t.label, color = C.Text, fontSize = 12.sp)
                    }
                }
                OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(label, { label = it }, singleLine = true, label = { Text("Label (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("Watch rules match hardware identifiers and classes. Use them for your own devices, inventory, or awareness of tracker-type beacons near you.", style = MonoSmall)
            }
        },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onAdd(type, value, label.ifBlank { null }) }) { Text("ADD", color = C.Green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = C.Dim) } },
    )
}
