package com.sophia.signal.classify

import com.sophia.signal.model.*

/** Interpret a real observation conservatively; no ownership inference. */
data class EquipmentReport(
    val confidenceLevel: String,
    val applications: List<String>,
    val typicalSectors: List<String>,
    val alternatives: String,
    val limitations: String,
    val ownership: String = "Unverified",
    val sources: List<String> = emptyList(),
    val observedEvidence: List<String> = emptyList(),
    val manufacturerStatus: String = "Unverified",
)

object EquipmentReports {
    fun from(o: Observation): EquipmentReport {
        val entry = ReferenceCatalog.forCategory(o.cls.category)
        // Numeric confidence in the legacy classifier is a heuristic, NOT a calibrated probability.
        val specificEvidence = o.cls.basis.any {
            it.contains("service", ignoreCase = true) ||
            it.contains("frame", ignoreCase = true) ||
            it.contains("manufacturer data", ignoreCase = true)
        }
        val level = when {
            o.cls.category == Category.UNKNOWN || o.cls.basis.isEmpty() -> "UNVERIFIED"
            specificEvidence && o.cls.confidence >= 75 -> "MEDIUM"
            else -> "LOW"
        }
        val evidence = buildList {
            add("${o.radio.label} observation at ${o.lastSeen} (device wall-clock milliseconds)")
            add("Measured RSSI ${o.rssi} dBm; ${o.count} sighting(s) in this session")
            o.raw.frequency?.let { add("Reported frequency $it MHz") }
            o.raw.channel?.let { add("Reported Wi-Fi channel $it") }
            if (o.raw.companyIds.isNotEmpty()) add("Advertised BLE company IDs: ${o.raw.companyIds.joinToString()}")
            addAll(o.cls.basis)
        }
        return EquipmentReport(
            confidenceLevel = level,
            applications = entry.applications,
            typicalSectors = entry.sectors,
            alternatives = entry.alternatives,
            limitations = "Single-device RSSI is not distance, bearing or location. Broadcast names and addresses may be randomized or spoofed. Sector examples are not operator attribution. Confidence is qualitative, not a probability.",
            sources = ReferenceCatalog.sourcesFor(o.cls.category).map { "${it.title}: ${it.url} (catalog review ${it.reviewed})" },
            observedEvidence = evidence,
            manufacturerStatus = if (o.cls.vendor.isNullOrBlank()) "Not identified" else "Vendor match: ${o.cls.vendor}; manufacturer of this specific product unverified",
        )
    }
}
