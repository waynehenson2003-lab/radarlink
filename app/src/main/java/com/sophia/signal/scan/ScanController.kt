package com.sophia.signal.scan

import android.content.Context
import com.sophia.signal.data.Prefs
import com.sophia.signal.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EngineState(
    val running: Boolean = false,
    val status: String = "Idle",
    val error: String? = null,
    val startedAt: Long = 0,
    val lastResultAt: Long = 0,
    val lastBatch: Int = 0,
    val visible: Int = 0,
    val requested: Int = 0,
    val rejected: Int = 0,
)

/** Owns both engines. Wi-Fi and BLE are controlled independently. */
class ScanController(private val app: Context, private val repo: Repository, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _wifi = MutableStateFlow(EngineState())
    val wifiState: StateFlow<EngineState> = _wifi.asStateFlow()
    private val _ble = MutableStateFlow(EngineState())
    val bleState: StateFlow<EngineState> = _ble.asStateFlow()

    private val wifi = WifiEngine(app, repo::ingest, _wifi) { prefs.settings.value.wifiIntervalSec }
    private val ble = BleEngine(app, repo::ingest, _ble) { prefs.settings.value.bleMode }

    fun startWifi() {
        repo.ensureSession()
        wifi.start(scope)
        sync()
    }

    fun stopWifi() { wifi.stop(); sync() }

    fun startBle() {
        repo.ensureSession()
        ble.start(scope)
        sync()
    }

    fun stopBle() { ble.stop(); sync() }

    fun startAll() { startWifi(); startBle() }
    fun stopAll() { stopWifi(); stopBle() }

    fun reapplyBleMode() { scope.launch { ble.applyMode() } }

    private fun sync() {
        val any = _wifi.value.running || _ble.value.running
        if (any) {
            if (prefs.settings.value.foregroundService) ScanService.start(app)
        } else {
            ScanService.stop(app)
            repo.endSession()
        }
    }
}
