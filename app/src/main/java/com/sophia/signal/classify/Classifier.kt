package com.sophia.signal.classify

import com.sophia.signal.data.Settings
import com.sophia.signal.model.Category
import com.sophia.signal.model.Classification
import com.sophia.signal.model.Radio
import com.sophia.signal.model.RawSighting
import kotlin.math.max

/**
 * Rule-based, explainable classification. It identifies hardware *classes* and manufacturers
 * only; it never attributes a signal to a person, vehicle or organization.
 */
object Classifier {

    private val HOTSPOT = listOf(
        "hotspot", "mifi", "jetpack", "iphone", "galaxy", "androidap", "android_", "pixel",
        "oneplus", "redmi", "mobile wifi", "portable",
    )
    private val PUBLIC = listOf("xfinitywifi", "attwifi", "spectrumwifi", "cablewifi", "optimumwifi", "boingo")

    fun isLocallyAdministered(mac: String): Boolean {
        val b = mac.take(2).toIntOrNull(16) ?: return false
        return b and 0x02 != 0
    }

    fun classify(raw: RawSighting, oui: OuiRegistry, s: Settings): Classification =
        if (raw.radio == Radio.WIFI) wifi(raw, oui, s) else ble(raw, oui, s)

    private fun wifi(raw: RawSighting, oui: OuiRegistry, s: Settings): Classification {
        val basis = mutableListOf<String>()
        var cat = Category.UNKNOWN
        var conf = 15
        var vendor: String? = null

        if (isLocallyAdministered(raw.id)) {
            basis += "BSSID ${raw.id.take(8)} is locally administered (virtual or randomized) — OUI lookup not meaningful"
        } else {
            val hit = oui.lookup(raw.id)
            if (hit != null) {
                vendor = hit.first
                cat = Vendors.categoryOf(hit.first)
                conf = if (hit.second.startsWith("IEEE")) 80 else 70
                basis += "OUI ${raw.id.take(8).uppercase()} → ${hit.first} (${hit.second})"
                basis += "Category from vendor profile: ${cat.label}"
            } else {
                basis += "OUI ${raw.id.take(8).uppercase()} not in local database"
            }
        }

        val ssid = raw.name.orEmpty()
        when {
            ssid.isEmpty() -> basis += "Hidden / empty SSID"
            HOTSPOT.any { ssid.contains(it, ignoreCase = true) } -> {
                if (cat == Category.UNKNOWN || cat == Category.COMMERCIAL) {
                    cat = Category.CELLULAR; conf = max(conf, 50)
                }
                basis += "SSID naming resembles a mobile hotspot (heuristic)"
            }
            PUBLIC.any { ssid.contains(it, ignoreCase = true) } -> {
                if (cat == Category.UNKNOWN) { cat = Category.INFRASTRUCTURE; conf = max(conf, 55) }
                basis += "SSID matches public / carrier hotspot naming (heuristic)"
            }
            ssid.startsWith("DIRECT-") -> {
                if (cat == Category.UNKNOWN) { cat = Category.COMMERCIAL; conf = max(conf, 60) }
                basis += "Wi-Fi Direct group owner (printers, TVs, cameras)"
            }
        }
        raw.security?.let { basis += "Security: $it" }
        if (cat == Category.UNKNOWN) basis += "No vendor or naming match — only an access-point beacon was observed"

        val (p, reason) = priority(cat, raw.rssi, s)
        return Classification(cat, vendor, conf, basis, p, reason)
    }

    private fun ble(raw: RawSighting, oui: OuiRegistry, s: Settings): Classification {
        val basis = mutableListOf<String>()
        var cat = Category.UNKNOWN
        var conf = 15
        var vendor: String? = null

        val services = raw.serviceUuids.map { BleServices.short(it) }
        services.forEach { u -> BleServices.MAP[u]?.let { basis += "Service 0x$u ($it)" } }

        // Manufacturer-specific data (Bluetooth SIG company IDs)
        for (cid in raw.companyIds) {
            val name = BleCompanies.MAP[cid]
            basis += "Manufacturer data company ID 0x%04X%s".format(cid, if (name != null) " → $name" else " (not in local table)")
            if (name != null && vendor == null) {
                vendor = name
                cat = if (Vendors.categoryOf(name) == Category.BT_ACCESSORY) Category.BT_ACCESSORY else Category.COMMERCIAL
                conf = 40
            }
        }

        if (0x004C in raw.companyIds) {
            when (raw.appleType) {
                0x12 -> { cat = Category.TRACKER; conf = 60; basis += "Apple Find My offline-finding frame (AirTag, Find My accessory, or an offline Apple device)" }
                0x07 -> { cat = Category.BT_ACCESSORY; conf = 65; basis += "Apple proximity-pairing frame (AirPods / Beats-class audio)" }
                0x02 -> { cat = Category.BEACON; conf = 80; basis += "iBeacon frame" }
                0x10 -> { cat = Category.COMMERCIAL; conf = 55; basis += "Apple Nearby Info frame (iPhone / iPad / Mac / Watch class)" }
                0x09 -> { cat = Category.COMMERCIAL; conf = 55; basis += "Apple AirPlay target frame" }
                0x0C -> { cat = Category.COMMERCIAL; conf = 55; basis += "Apple Handoff frame" }
                null -> {}
                else -> basis += "Apple continuity type 0x%02X".format(raw.appleType)
            }
        }

        when {
            "FEED" in services || "FEEC" in services -> { cat = Category.TRACKER; conf = max(conf, 65); vendor = vendor ?: "Tile" }
            "FD5A" in services -> { cat = Category.TRACKER; conf = max(conf, 60); vendor = vendor ?: "Samsung" }
            "FEAA" in services -> { if (cat == Category.UNKNOWN || cat == Category.COMMERCIAL) { cat = Category.BEACON; conf = max(conf, 75) } }
            "FD6F" in services -> { if (cat == Category.UNKNOWN) { cat = Category.COMMERCIAL; conf = max(conf, 60) }; basis += "Exposure Notification is phone-class" }
            services.any { it in setOf("180D", "1816", "1818", "1826", "1812", "1809", "1810") } -> {
                if (cat == Category.UNKNOWN || cat == Category.COMMERCIAL) { cat = Category.BT_ACCESSORY; conf = max(conf, 60) }
            }
        }
        raw.beaconInfo?.let { basis += it }

        if (vendor == null && !isLocallyAdministered(raw.id)) {
            oui.lookup(raw.id)?.let { (v, src) ->
                vendor = v
                basis += "Address OUI → $v ($src) — valid only if this is a public address"
                if (cat == Category.UNKNOWN) { cat = Vendors.categoryOf(v).let { c -> if (c == Category.ROUTER) Category.COMMERCIAL else c }; conf = 35 }
            }
        } else if (isLocallyAdministered(raw.id) && raw.companyIds.isEmpty()) {
            basis += "Address appears random (resolvable/static) — device identity cannot be established from it"
        }

        if (cat == Category.UNKNOWN) basis += "No manufacturer data or recognized services in advertisement"
        val (p, reason) = priority(cat, raw.rssi, s)
        return Classification(cat, vendor, conf, basis, p, reason)
    }

    private fun priority(cat: Category, rssi: Int, s: Settings): Pair<Boolean, String?> = when {
        s.ruleTrackers && cat == Category.TRACKER -> true to "Rule: tracker-type beacon"
        s.ruleCellular && cat == Category.CELLULAR -> true to "Rule: cellular-class hardware"
        s.ruleUnknownStrong && cat == Category.UNKNOWN && rssi >= -50 -> true to "Rule: strong unknown emitter (≥ -50 dBm)"
        else -> false to null
    }
}
