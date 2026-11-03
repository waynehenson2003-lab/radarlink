package com.sophia.signal.data

import android.content.Context
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val wifiIntervalSec: Int = 30,
    val bleMode: Int = ScanSettings.SCAN_MODE_BALANCED,
    val rssiFloor: Int = -100,
    val alertsEnabled: Boolean = true,
    val alertTone: Int = 0,
    val alertVolume: Int = 80,
    val vibrate: Boolean = true,
    val cooldownMin: Int = 10,
    val alertOnPriority: Boolean = true,
    val ruleTrackers: Boolean = true,
    val ruleCellular: Boolean = true,
    val ruleUnknownStrong: Boolean = false,
    val retentionDays: Int = 90,
    val foregroundService: Boolean = true,
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sophia_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            wifiIntervalSec = sp.getInt("wifiIntervalSec", d.wifiIntervalSec),
            bleMode = sp.getInt("bleMode", d.bleMode),
            rssiFloor = sp.getInt("rssiFloor", d.rssiFloor),
            alertsEnabled = sp.getBoolean("alertsEnabled", d.alertsEnabled),
            alertTone = sp.getInt("alertTone", d.alertTone),
            alertVolume = sp.getInt("alertVolume", d.alertVolume),
            vibrate = sp.getBoolean("vibrate", d.vibrate),
            cooldownMin = sp.getInt("cooldownMin", d.cooldownMin),
            alertOnPriority = sp.getBoolean("alertOnPriority", d.alertOnPriority),
            ruleTrackers = sp.getBoolean("ruleTrackers", d.ruleTrackers),
            ruleCellular = sp.getBoolean("ruleCellular", d.ruleCellular),
            ruleUnknownStrong = sp.getBoolean("ruleUnknownStrong", d.ruleUnknownStrong),
            retentionDays = sp.getInt("retentionDays", d.retentionDays),
            foregroundService = sp.getBoolean("foregroundService", d.foregroundService),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        sp.edit()
            .putInt("wifiIntervalSec", s.wifiIntervalSec)
            .putInt("bleMode", s.bleMode)
            .putInt("rssiFloor", s.rssiFloor)
            .putBoolean("alertsEnabled", s.alertsEnabled)
            .putInt("alertTone", s.alertTone)
            .putInt("alertVolume", s.alertVolume)
            .putBoolean("vibrate", s.vibrate)
            .putInt("cooldownMin", s.cooldownMin)
            .putBoolean("alertOnPriority", s.alertOnPriority)
            .putBoolean("ruleTrackers", s.ruleTrackers)
            .putBoolean("ruleCellular", s.ruleCellular)
            .putBoolean("ruleUnknownStrong", s.ruleUnknownStrong)
            .putInt("retentionDays", s.retentionDays)
            .putBoolean("foregroundService", s.foregroundService)
            .apply()
        _settings.value = s
    }
}
