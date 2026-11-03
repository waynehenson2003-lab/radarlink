package com.sophia.signal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.model.ObsRow
import com.sophia.signal.model.Radio
import com.sophia.signal.model.WatchType

@Composable
fun DetailDialog(key: String, onDismiss: () -> Unit) {
    val app = sophia()
    val live by app.repo.live.collectAsStateWithLifecycle()
    val session by app.repo.session.collectAsStateWithLifecycle()
    val o = live[key]
    val ident = app.repo.ident(key)
    var label by remember(key) { mutableStateOf(o?.label ?: ident?.label ?: "") }
    var samples by remember(key) { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }
    var history by remember(key) { mutableStateOf<List<ObsRow>>(emptyList()) }
    var watched by remember(key) { mutableStateOf(false) }
    val now = rememberNow(5000)

    LaunchedEffect(key, session?.id, now) {
        session?.id?.let { samples = app.repo.samples(it, key) }
    }
    LaunchedEffect(key) { history = app.repo.identHistory(key) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Panel,
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", color = C.Green) } },
        title = {
            Text(o?.label ?: o?.name ?: ident?.ident ?: key, color = C.Green, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (o == null) {
                    Text("Not in the current live view.", style = MonoSmall)
                } else {
                    val r = o.raw
                    KV("Radio", o.radio.label)
                    KV("Identifier", o.id)
                    KV("Name", o.name ?: "—")
                    KV("RSSI", "${o.rssi} dBm (min ${o.rssiMin}, max ${o.rssiMax}, Δ ${o.trend})")
                    KV("Sightings", "${o.count} this session")
                    KV("First / last", "${fmtTime(o.firstSeen)} / ${fmtTime(o.lastSeen)}")
                    KV("History", if (o.seenBefore) "Seen in ${o.priorSessions} earlier session(s)" else "First time observed")
                    if (o.radio == Radio.WIFI) {
                        KV("Frequency", listOfNotNull(r.frequency?.let { "$it MHz" }, r.band, r.channel?.let { "ch $it" }).joinToString(" · "))
                        KV("Width / std", listOfNotNull(r.channelWidth, r.standard).joinToString(" · ").ifEmpty { "not reported" })
                        KV("Security", r.security ?: "—")
                        KV("Capabilities", r.capabilities ?: "—")
                    } else {
                        KV("Companies", r.companyIds.joinToString { "0x%04X".format(it) }.ifEmpty { "none broadcast" })
                        r.mfgHex.forEach { (k, v) -> KV("Mfg 0x%04X".format(k), v.take(64) + if (v.length > 64) "…" else "") }
                        KV("Services", r.serviceUuids.joinToString { com.sophia.signal.classify.BleServices.short(it) }.ifEmpty { "none" })
                        KV("TX power", r.txPower?.let { "$it dBm" } ?: "not advertised")
                        KV("Connectable", r.connectable?.toString() ?: "unknown")
                        r.beaconInfo?.let { KV("Beacon", it) }
                        val similar = live.values.count { it.radio == Radio.BLE && it.raw.fingerprint == r.fingerprint }
                        if (similar > 1) Text(
                            "$similar addresses share this payload fingerprint — possibly one device rotating its address, or several identical models. Low confidence.",
                            color = C.Violet, fontSize = 11.sp,
                        )
                    }

                    HorizontalDivider(color = C.Line)
                    Text("CLASSIFICATION", color = catColor(o.cls.category), fontSize = 11.sp, letterSpacing = 2.sp)
                    KV("Category", o.cls.category.label)
                    KV("Vendor", o.cls.vendor ?: "unidentified")
                    KV("Confidence", "${o.cls.confidence}%")
                    o.cls.priorityReason?.let { KV("Priority", it) }
                    o.watchHit?.let { KV("Watch", it) }
                    Text("Supporting evidence:", style = MonoSmall)
                    o.cls.basis.forEach { Text("• $it", color = C.Text, fontSize = 11.sp) }
                    Text("Classification describes hardware type/maker only — never an owner.", style = MonoSmall)

                    if (samples.size >= 2) {
                        HorizontalDivider(color = C.Line)
                        Text("RSSI THIS SESSION (${samples.size} samples)", color = C.Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
                        Sparkline(samples)
                    }
                }

                if (history.isNotEmpty()) {
                    HorizontalDivider(color = C.Line)
                    Text("SESSION HISTORY", color = C.Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
                    history.take(12).forEach { h ->
                        Text("#${h.sessionId} ${fmtDate(h.firstSeen)} · max ${h.rssiMax} dBm · ${h.count}×", style = MonoSmall)
                    }
                }

                HorizontalDivider(color = C.Line)
                OutlinedTextField(
                    value = label, onValueChange = { label = it }, singleLine = true,
                    label = { Text("Custom label (encrypted on device)") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Green, unfocusedBorderColor = C.Line),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("SAVE LABEL") { app.repo.setLabel(key, label) }
                    val flagged = o?.flagged ?: ident?.flagged ?: false
                    ActionButton(if (flagged) "UNFLAG" else "FLAG", color = C.Amber) { app.repo.setFlag(key, !flagged) }
                }
                ActionButton(if (watched) "ADDED TO WATCHLIST" else "WATCH THIS IDENTIFIER", color = C.Red, enabled = !watched) {
                    val id = o?.id ?: ident?.ident ?: return@ActionButton
                    app.repo.addWatch(WatchType.EXACT_ID, id, label.ifBlank { o?.name })
                    watched = true
                }
            }
        },
    )
}

@Composable
private fun KV(k: String, v: String) {
    Row {
        Text(k, color = C.Dim, fontSize = 11.sp, modifier = Modifier.width(96.dp))
        Text(v, color = C.Text, fontSize = 11.sp)
    }
}

@Composable
fun Sparkline(points: List<Pair<Long, Int>>) {
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val t0 = points.first().first
        val t1 = points.last().first.coerceAtLeast(t0 + 1)
        val lo = (points.minOf { it.second } - 3).toFloat()
        val hi = (points.maxOf { it.second } + 3).toFloat()
        fun pos(p: Pair<Long, Int>) = Offset(
            (p.first - t0).toFloat() / (t1 - t0) * size.width,
            size.height - (p.second - lo) / (hi - lo) * size.height,
        )
        val path = Path()
        points.forEachIndexed { i, p -> val o = pos(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawLine(C.Line, Offset(0f, size.height), Offset(size.width, size.height))
        drawPath(path, C.Cyan, style = Stroke(2.dp.toPx()))
    }
}
