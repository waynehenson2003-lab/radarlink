package com.sophia.signal.ui

import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sophia.signal.alert.Alerts
import com.sophia.signal.scan.Perms

@Composable
fun SettingsScreen() {
    val app = sophia()
    val ctx = LocalContext.current
    val s by app.prefs.settings.collectAsStateWithLifecycle()
    val ouiCount by app.repo.ouiImportedCount.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var confirmWipe by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val ouiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val lines = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() } ?: emptyList()
            }.getOrDefault(emptyList())
            app.repo.importOui(lines.asSequence()) { n ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(ctx, if (n > 0) "Imported $n OUI entries" else "No OUI entries found in file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        key(tick) {
            Panel("Permissions") {
                Perms.required().forEach { p ->
                    val (title, why) = Perms.explain(p)
                    val ok = Perms.granted(ctx, p)
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(if (ok) "✓" else "✗", color = if (ok) C.Green else C.Red, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                        Column {
                            Text(title, color = C.Text, fontSize = 13.sp)
                            Text(why, style = MonoSmall)
                        }
                    }
                }
                val locOn = Perms.locationServicesOn(ctx)
                Text(
                    if (locOn) "Location services: ON" else "Location services: OFF — Android returns empty Wi-Fi scan results while off.",
                    color = if (locOn) C.Green else C.Amber, fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("REQUEST") { permLauncher.launch(Perms.required().toTypedArray()) }
                    ActionButton("APP SETTINGS", color = C.Cyan) {
                        ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }
                    ActionButton("LOCATION", color = C.Cyan) { ctx.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
            }
        }

        Panel("Scanning", accent = C.Cyan) {
            Text("Wi-Fi scan interval: ${s.wifiIntervalSec}s", color = C.Text, fontSize = 12.sp)
            Slider(
                value = s.wifiIntervalSec.toFloat(), onValueChange = { v -> app.prefs.update { it.copy(wifiIntervalSec = v.toInt()) } },
                valueRange = 10f..300f, colors = SliderDefaults.colors(thumbColor = C.Cyan, activeTrackColor = C.Cyan),
            )
            Text("Android throttles to 4 scans / 2 min. Below 30 s, extra requests are rejected and the OS cache is read instead (no duplicates are counted).", style = MonoSmall)
            Spacer(Modifier.height(8.dp))
            Text("BLE scan mode", color = C.Text, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("LOW POWER" to ScanSettings.SCAN_MODE_LOW_POWER, "BALANCED" to ScanSettings.SCAN_MODE_BALANCED, "LOW LATENCY" to ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .forEach { (label, mode) ->
                        SelChip(label, s.bleMode == mode) { app.prefs.update { it.copy(bleMode = mode) }; app.scanner.reapplyBleMode() }
                    }
            }
            Text("Low latency finds more advertisements but uses noticeably more battery.", style = MonoSmall)
            Spacer(Modifier.height(8.dp))
            Text("Ignore signals weaker than ${s.rssiFloor} dBm", color = C.Text, fontSize = 12.sp)
            Slider(
                value = s.rssiFloor.toFloat(), onValueChange = { v -> app.prefs.update { it.copy(rssiFloor = v.toInt()) } },
                valueRange = -100f..-40f, colors = SliderDefaults.colors(thumbColor = C.Cyan, activeTrackColor = C.Cyan),
            )
            ToggleRow("Foreground service (keep scanning with screen off)", s.foregroundService) { v -> app.prefs.update { it.copy(foregroundService = v) } }
            Text("Samsung, Xiaomi and others may still stop background work. If scans stop with the screen off, set SØPHIA's battery usage to Unrestricted.", style = MonoSmall)
            ActionButton("BATTERY OPTIMIZATION SETTINGS", color = C.Cyan) {
                runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        Panel("Alerts", accent = C.Red) {
            ToggleRow("Audio / vibration / notification alerts", s.alertsEnabled) { v -> app.prefs.update { it.copy(alertsEnabled = v) } }
            ToggleRow("Alert on priority classifications", s.alertOnPriority) { v -> app.prefs.update { it.copy(alertOnPriority = v) } }
            ToggleRow("Vibrate", s.vibrate) { v -> app.prefs.update { it.copy(vibrate = v) } }
            Text("Tone", color = C.Text, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Alerts.TONES.forEachIndexed { i, t -> SelChip(t.first, s.alertTone == i) { app.prefs.update { it.copy(alertTone = i) } } }
            }
            Text("Volume ${s.alertVolume}%", color = C.Text, fontSize = 12.sp)
            Slider(
                value = s.alertVolume.toFloat(), onValueChange = { v -> app.prefs.update { it.copy(alertVolume = v.toInt()) } },
                valueRange = 0f..100f, colors = SliderDefaults.colors(thumbColor = C.Red, activeTrackColor = C.Red),
            )
            Text("Re-alert cooldown: ${s.cooldownMin} min", color = C.Text, fontSize = 12.sp)
            Slider(
                value = s.cooldownMin.toFloat(), onValueChange = { v -> app.prefs.update { it.copy(cooldownMin = v.toInt()) } },
                valueRange = 1f..120f, colors = SliderDefaults.colors(thumbColor = C.Red, activeTrackColor = C.Red),
            )
            ActionButton("TEST TONE", color = C.Red) { app.alerts.playTone() }
        }

        Panel("Priority rules", accent = C.Amber) {
            ToggleRow("Tracker-type beacons (Find My, Tile, SmartTag-class)", s.ruleTrackers) { v -> app.prefs.update { it.copy(ruleTrackers = v) } }
            ToggleRow("Cellular-class hardware (hotspots, cellular routers)", s.ruleCellular) { v -> app.prefs.update { it.copy(ruleCellular = v) } }
            ToggleRow("Strong unknown emitters (≥ -50 dBm)", s.ruleUnknownStrong) { v -> app.prefs.update { it.copy(ruleUnknownStrong = v) } }
            Text("Rules apply to new sightings. Watchlists live in PRIORITY.", style = MonoSmall)
        }

        Panel("OUI database", accent = C.Violet) {
            Text("Built-in seed: ${app.repo.oui.builtinCount} prefixes · Imported IEEE: $ouiCount prefixes", color = C.Text, fontSize = 12.sp)
            Text(
                "For full manufacturer coverage, download the IEEE MA-L registry (oui.csv, from standards-oui.ieee.org) on any device, " +
                    "copy it to this phone, and import it here. SØPHIA has no internet permission and never fetches it itself.",
                style = MonoSmall,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("IMPORT OUI.CSV", color = C.Violet) { ouiPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }
                ActionButton("CLEAR IMPORTED", color = C.Dim, enabled = ouiCount > 0) { app.repo.clearOui() }
            }
        }

        Panel("Storage", accent = C.Dim) {
            Text("Keep history for ${s.retentionDays} days (0 = forever)", color = C.Text, fontSize = 12.sp)
            Slider(
                value = s.retentionDays.toFloat(), onValueChange = { v -> app.prefs.update { it.copy(retentionDays = v.toInt()) } },
                valueRange = 0f..365f, colors = SliderDefaults.colors(thumbColor = C.Green, activeTrackColor = C.Green),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("PURGE OLD NOW") {
                    app.repo.purge(s.retentionDays) { n ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, "Removed $n session(s)", Toast.LENGTH_SHORT).show() }
                    }
                }
                ActionButton("DELETE ALL HISTORY", color = C.Red) { confirmWipe = true }
            }
            Text("Labels and watchlist values are encrypted with an Android Keystore key. Backups are disabled.", style = MonoSmall)
        }

        Panel("Privacy & platform limits", accent = C.Green) {
            listOf(
                "No INTERNET permission, no telemetry, no cloud. Exports only go where you save them.",
                "Microphone and camera are never used. No connections, pairing, packet capture, or decryption.",
                "Wi-Fi: Android exposes SSID, BSSID, RSSI, frequency, channel width, standard and security flags only.",
                "BLE: random/rotating addresses mean one device can appear as many; identity cannot be established.",
                "Radar shows signal strength only. A single phone cannot measure direction or distance to a transmitter.",
                "Unfiltered BLE scans are paused by Android while the screen is off on many devices.",
            ).forEach { Text("• $it", color = C.Text, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp)) }
        }
    }

    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false }, containerColor = C.Panel,
        title = { Text("Delete all history?", color = C.Red) },
        text = { Text("Removes every stored session, sample and watch match (except an active session). Watch rules and the OUI database are kept.", style = MonoSmall) },
        confirmButton = { TextButton(onClick = { app.repo.deleteAllHistory(); confirmWipe = false }) { Text("DELETE", color = C.Red) } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("CANCEL", color = C.Dim) } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = C.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = C.Green, checkedTrackColor = C.Green.copy(alpha = 0.3f)))
    }
}
