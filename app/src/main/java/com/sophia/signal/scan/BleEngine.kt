package com.sophia.signal.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.sophia.signal.model.Radio
import com.sophia.signal.model.RawSighting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive BLE advertisement listener. Never connects, pairs, or sends anything.
 *
 * Android limits: max 5 scan starts per 30 s (excess starts fail silently), unfiltered scans
 * pause while the screen is off on most builds, and scans longer than 30 min are downgraded
 * to opportunistic. We rate-limit starts and restart every 25 min to stay within these rules.
 */
class BleEngine(
    private val ctx: Context,
    private val sink: (List<RawSighting>) -> Unit,
    private val state: MutableStateFlow<EngineState>,
    private val scanMode: () -> Int,
) {
    private val mgr = ctx.getSystemService(BluetoothManager::class.java)
    private var scanner: BluetoothLeScanner? = null
    private val buffer = ConcurrentHashMap<String, RawSighting>()
    private val startTimes = ArrayDeque<Long>()
    private var flushJob: Job? = null
    private var restartJob: Job? = null
    @Volatile private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            val msg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "app registration failed (toggle Bluetooth and retry)"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal Bluetooth stack error"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported on this device"
                5 -> "out of hardware resources"
                6 -> "scanning too frequently (Android limit: 5 starts / 30 s)"
                else -> "error $errorCode"
            }
            state.update { it.copy(error = "BLE scan failed: $msg") }
        }
    }

    fun start(scope: CoroutineScope): Boolean {
        if (flushJob != null) return true
        if (!Perms.bleOk(ctx)) {
            state.update { it.copy(running = false, error = "Missing permission: nearby devices / location") }
            return false
        }
        val adapter = mgr?.adapter
        if (adapter == null) {
            state.update { it.copy(running = false, error = "This device has no Bluetooth adapter") }
            return false
        }
        if (!adapter.isEnabled) {
            state.update { it.copy(running = false, error = "Bluetooth is off — turn it on in Quick Settings") }
            return false
        }
        state.update { EngineState(running = true, status = "Starting", startedAt = System.currentTimeMillis()) }
        flushJob = scope.launch {
            startScanRateLimited()
            while (isActive) {
                delay(1000)
                if (buffer.isNotEmpty()) {
                    val batch = buffer.values.toList()
                    buffer.clear()
                    sink(batch)
                    state.update { it.copy(lastResultAt = System.currentTimeMillis(), lastBatch = batch.size, status = if (scanning) "Listening for advertisements" else it.status) }
                }
            }
        }
        restartJob = scope.launch {
            while (isActive) {
                delay(25 * 60_000L)
                stopScan()
                startScanRateLimited()
            }
        }
        return true
    }

    fun stop() {
        flushJob?.cancel(); flushJob = null
        restartJob?.cancel(); restartJob = null
        stopScan()
        buffer.clear()
        state.update { it.copy(running = false, status = "Stopped") }
    }

    /** Re-applies the scan mode after a settings change. */
    suspend fun applyMode() {
        if (flushJob == null) return
        stopScan(); startScanRateLimited()
    }

    private suspend fun startScanRateLimited() {
        val now = System.currentTimeMillis()
        while (startTimes.isNotEmpty() && now - startTimes.first() > 30_000) startTimes.removeFirst()
        if (startTimes.size >= 4) {
            val wait = 30_000 - (now - startTimes.first()) + 500
            state.update { it.copy(status = "Waiting ${wait / 1000}s to respect Android scan-start limit") }
            delay(wait)
        }
        startTimes.addLast(System.currentTimeMillis())
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = mgr?.adapter ?: return
        val sc = adapter.bluetoothLeScanner
        if (sc == null) {
            state.update { it.copy(error = "BLE scanner unavailable (is Bluetooth on?)") }; return
        }
        val settings = ScanSettings.Builder().setScanMode(scanMode()).setReportDelay(0).build()
        try {
            sc.startScan(null, settings, callback)
            scanner = sc
            scanning = true
            state.update { it.copy(status = "Listening for advertisements", error = null, requested = it.requested + 1) }
        } catch (e: SecurityException) {
            state.update { it.copy(error = "Permission denied by Android: ${e.message}") }
        } catch (e: IllegalStateException) {
            state.update { it.copy(error = "Bluetooth turned off") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { scanner?.stopScan(callback) } catch (e: Exception) { }
        scanner = null
        scanning = false
    }

    private fun handle(r: ScanResult) {
        val addr = r.device?.address?.uppercase() ?: return
        val rec = r.scanRecord
        val companies = ArrayList<Int>()
        val mfg = HashMap<Int, String>()
        rec?.manufacturerSpecificData?.let { sa ->
            for (i in 0 until sa.size()) {
                val k = sa.keyAt(i)
                companies += k
                mfg[k] = sa.valueAt(i)?.toHex() ?: ""
            }
        }
        val services = LinkedHashSet<String>()
        rec?.serviceUuids?.forEach { services += it.uuid.toString() }
        rec?.serviceData?.keys?.forEach { services += it.uuid.toString() }

        val apple = rec?.manufacturerSpecificData?.get(0x004C)
        val appleType = apple?.getOrNull(0)?.toInt()?.and(0xFF)
        var beacon: String? = null
        if (apple != null && appleType == 0x02 && apple.size >= 23) {
            val uuid = apple.copyOfRange(2, 18).toHex()
            val major = ((apple[18].toInt() and 0xFF) shl 8) or (apple[19].toInt() and 0xFF)
            val minor = ((apple[20].toInt() and 0xFF) shl 8) or (apple[21].toInt() and 0xFF)
            beacon = "iBeacon uuid=$uuid major=$major minor=$minor"
        }
        val eddy = rec?.getServiceData(ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb"))
        if (eddy != null && eddy.isNotEmpty()) {
            beacon = "Eddystone frame " + when (eddy[0].toInt() and 0xFF) {
                0x00 -> "UID"; 0x10 -> "URL"; 0x20 -> "TLM"; 0x30 -> "EID"; else -> "0x%02X".format(eddy[0])
            }
        }
        val tx = if (Build.VERSION.SDK_INT >= 26 && r.txPower != ScanResult.TX_POWER_NOT_PRESENT) r.txPower
        else rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }

        buffer[addr] = RawSighting(
            radio = Radio.BLE, id = addr, name = rec?.deviceName?.takeIf { it.isNotBlank() },
            rssi = r.rssi, ts = System.currentTimeMillis(),
            companyIds = companies, mfgHex = mfg, serviceUuids = services.toList(),
            txPower = tx, connectable = if (Build.VERSION.SDK_INT >= 26) r.isConnectable else null,
            appleType = appleType, beaconInfo = beacon,
        )
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
