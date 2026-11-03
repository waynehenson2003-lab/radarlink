package com.sophia.signal.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.sophia.signal.model.Radio
import com.sophia.signal.model.RawSighting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Passive Wi-Fi observation via WifiManager scan results (AP beacons/probe responses that
 * the OS already collects). No association, no packet capture.
 *
 * Android 9+ throttles foreground apps to 4 startScan() calls per 2 minutes (1 per 30 min in
 * background). When a request is rejected we read the OS cache but only count results whose
 * own timestamp changed, so nothing is double-counted or fabricated.
 */
class WifiEngine(
    private val ctx: Context,
    private val sink: (List<RawSighting>) -> Unit,
    private val state: MutableStateFlow<EngineState>,
    private val intervalSec: () -> Int,
) {
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var job: Job? = null
    private var registered = false
    private val lastTs = HashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val updated = i?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            deliver(if (updated) "Fresh scan results received" else "OS reported no refresh (throttled or failed)")
        }
    }

    fun start(scope: CoroutineScope): Boolean {
        if (job != null) return true
        if (!Perms.wifiOk(ctx)) {
            state.update { it.copy(running = false, error = "Missing permission: location / nearby Wi-Fi devices") }
            return false
        }
        if (!registered) {
            ContextCompat.registerReceiver(
                ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
        }
        state.update { EngineState(running = true, status = "Starting", startedAt = System.currentTimeMillis()) }
        job = scope.launch {
            while (isActive) {
                request()
                delay(intervalSec().coerceIn(5, 600) * 1000L)
            }
        }
        return true
    }

    fun stop() {
        job?.cancel(); job = null
        if (registered) { runCatching { ctx.unregisterReceiver(receiver) }; registered = false }
        state.update { it.copy(running = false, status = "Stopped") }
    }

    @Suppress("DEPRECATION")
    private fun request() {
        if (!Perms.wifiOk(ctx)) {
            state.update { it.copy(error = "Permission revoked") }; return
        }
        val scanAlways = runCatching { wm.isScanAlwaysAvailable }.getOrDefault(false)
        if (!wm.isWifiEnabled && !scanAlways) {
            state.update { it.copy(error = "Wi-Fi is off and 'Wi-Fi scanning' (Location settings) is disabled") }
            return
        }
        if (!Perms.locationServicesOn(ctx)) {
            state.update { it.copy(error = "Location services are off — Android returns no Wi-Fi results") }
        } else state.update { it.copy(error = null) }

        val ok = try { wm.startScan() } catch (e: SecurityException) { false }
        state.update { it.copy(requested = it.requested + 1, rejected = it.rejected + if (ok) 0 else 1) }
        if (!ok) deliver("startScan rejected by Android throttle — reading OS cache (only fresh entries counted)")
    }

    @Suppress("DEPRECATION")
    private fun deliver(status: String) {
        val results: List<ScanResult> = try { wm.scanResults ?: emptyList() } catch (e: SecurityException) { emptyList() }
        val nowMs = System.currentTimeMillis()
        val bootUs = SystemClock.elapsedRealtimeNanos() / 1000
        val out = ArrayList<RawSighting>()
        for (r in results) {
            val bssid = r.BSSID?.uppercase() ?: continue
            if (bssid == "02:00:00:00:00:00") continue // redacted by OS (missing permission)
            val ageMs = (bootUs - r.timestamp) / 1000
            if (ageMs > 180_000) continue
            if (lastTs[bssid] == r.timestamp) continue
            lastTs[bssid] = r.timestamp
            out += toSighting(r, bssid, nowMs - ageMs)
        }
        sink(out)
        state.update { it.copy(status = status, lastResultAt = nowMs, lastBatch = out.size, visible = results.size) }
    }

    @Suppress("DEPRECATION")
    private fun toSighting(r: ScanResult, bssid: String, ts: Long): RawSighting {
        val ssid: String? = if (Build.VERSION.SDK_INT >= 33) {
            r.wifiSsid?.toString()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        } else r.SSID?.takeIf { it.isNotEmpty() }
        val f = r.frequency
        return RawSighting(
            radio = Radio.WIFI, id = bssid, name = ssid, rssi = r.level, ts = ts,
            frequency = f, channel = channelOf(f), band = bandOf(f),
            security = securityOf(r.capabilities ?: ""), capabilities = r.capabilities,
            channelWidth = widthOf(r.channelWidth),
            standard = if (Build.VERSION.SDK_INT >= 30) standardOf(r.wifiStandard) else null,
        )
    }

    companion object {
        fun channelOf(f: Int): Int? = when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2407) / 5
            f in 5160..5885 -> (f - 5000) / 5
            f in 5955..7115 -> (f - 5950) / 5
            f in 58320..70200 -> (f - 56160) / 2160
            else -> null
        }

        fun bandOf(f: Int): String = when {
            f < 3000 -> "2.4 GHz"
            f < 5925 -> "5 GHz"
            f < 7200 -> "6 GHz"
            else -> "60 GHz"
        }

        fun securityOf(c: String): String = when {
            c.contains("WPA3") || c.contains("SAE") -> "WPA3"
            c.contains("OWE") -> "OWE (Enhanced Open)"
            c.contains("WPA2") || c.contains("RSN") -> "WPA2"
            c.contains("WPA") -> "WPA"
            c.contains("WEP") -> "WEP"
            else -> "Open"
        }

        fun widthOf(w: Int): String = when (w) {
            0 -> "20 MHz"; 1 -> "40 MHz"; 2 -> "80 MHz"; 3 -> "160 MHz"; 4 -> "80+80 MHz"; 5 -> "320 MHz"
            else -> "unknown"
        }

        fun standardOf(s: Int): String? = when (s) {
            1 -> "802.11a/b/g"; 4 -> "802.11n (Wi-Fi 4)"; 5 -> "802.11ac (Wi-Fi 5)"
            6 -> "802.11ax (Wi-Fi 6/6E)"; 7 -> "802.11ad"; 8 -> "802.11be (Wi-Fi 7)"
            else -> null
        }
    }
}
