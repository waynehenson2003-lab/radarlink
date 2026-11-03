package com.sophia.signal.model

enum class Radio(val label: String) { WIFI("Wi-Fi"), BLE("BLE") }

enum class Category(val label: String, val short: String) {
    CELLULAR("Cellular networking equipment", "CELL"),
    ROUTER("Router / access point", "RTR"),
    INFRASTRUCTURE("Infrastructure hardware", "INFRA"),
    COMMERCIAL("Commercial wireless device", "COMM"),
    BT_ACCESSORY("Bluetooth accessory", "ACC"),
    TRACKER("Tracker-type beacon", "TRK"),
    BEACON("Beacon", "BCN"),
    UNKNOWN("Unknown hardware", "UNK"),
}

/** Every classification carries its confidence (0-100) and the evidence behind it. */
data class Classification(
    val category: Category,
    val vendor: String?,
    val confidence: Int,
    val basis: List<String>,
    val priority: Boolean,
    val priorityReason: String?,
)

/** One raw sighting exactly as reported by the Android API. Nothing here is synthesized. */
data class RawSighting(
    val radio: Radio,
    val id: String,                  // BSSID or BLE address, uppercase
    val name: String?,               // SSID or advertised local name
    val rssi: Int,
    val ts: Long,                    // wall-clock ms of the observation
    // Wi-Fi
    val frequency: Int? = null,
    val channel: Int? = null,
    val band: String? = null,
    val security: String? = null,
    val capabilities: String? = null,
    val channelWidth: String? = null,
    val standard: String? = null,
    // BLE
    val companyIds: List<Int> = emptyList(),
    val mfgHex: Map<Int, String> = emptyMap(),
    val serviceUuids: List<String> = emptyList(),
    val txPower: Int? = null,
    val connectable: Boolean? = null,
    val appleType: Int? = null,
    val beaconInfo: String? = null,
) {
    /** Payload fingerprint used to spot a likely rotating BLE address. Low-confidence by design. */
    val fingerprint: String
        get() = buildString {
            append(companyIds.sorted().joinToString(","))
            append('|'); append(serviceUuids.sorted().joinToString(","))
            append('|'); append(appleType ?: "")
            append('|'); append(name ?: "")
        }
}

data class Observation(
    val key: String,
    val radio: Radio,
    val id: String,
    val name: String?,
    val rssi: Int,
    val rssiMin: Int,
    val rssiMax: Int,
    val rssiPrev: Int?,
    val firstSeen: Long,
    val lastSeen: Long,
    val count: Int,
    val raw: RawSighting,
    val cls: Classification,
    val seenBefore: Boolean,
    val priorSessions: Int,
    val flagged: Boolean,
    val label: String?,
    val watchHit: String?,
) {
    val trend: Int get() = rssiPrev?.let { rssi - it } ?: 0
    val isPriority: Boolean get() = cls.priority || flagged || watchHit != null
}

data class Session(val id: Long, val started: Long, val ended: Long?, val wifi: Int, val ble: Int) {
    val total get() = wifi + ble
}

enum class WatchType(val label: String) {
    EXACT_ID("Exact identifier (BSSID / BLE address)"),
    SSID_CONTAINS("Name / SSID contains"),
    VENDOR_CONTAINS("Vendor contains"),
    BLE_COMPANY("BLE company ID (hex, e.g. 004C)"),
    CATEGORY("Category (e.g. TRACKER, CELLULAR)"),
}

data class WatchRule(
    val id: Long,
    val type: WatchType,
    val value: String,
    val label: String?,
    val enabled: Boolean,
    val created: Long,
)

data class PriorityEvent(
    val ts: Long,
    val key: String,
    val title: String,
    val detail: String,
    val watch: Boolean,
)

data class IdentRecord(
    val key: String,
    val radio: Radio,
    val ident: String,
    var firstSeen: Long,
    var lastSeen: Long,
    var sessions: Int,
    var lastSession: Long,
    var flagged: Boolean,
    var label: String?,
)

/** A stored per-session observation row (history). */
data class ObsRow(
    val sessionId: Long,
    val key: String,
    val radio: Radio,
    val ident: String,
    val name: String?,
    val vendor: String?,
    val category: Category,
    val confidence: Int,
    val priority: Boolean,
    val priorityReason: String?,
    val rssi: Int,
    val rssiMin: Int,
    val rssiMax: Int,
    val count: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val freq: Int?,
    val channel: Int?,
    val security: String?,
    val meta: String,
    val seenBefore: Boolean,
)

data class HitRow(val ts: Long, val sessionId: Long, val key: String, val watchId: Long, val rssi: Int, val note: String?)

data class SessionCompare(
    val a: Session,
    val b: Session,
    val onlyA: List<ObsRow>,
    val onlyB: List<ObsRow>,
    val both: List<Pair<ObsRow, ObsRow>>,
)
