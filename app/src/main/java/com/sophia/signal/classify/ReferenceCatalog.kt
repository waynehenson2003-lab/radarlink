package com.sophia.signal.classify

import com.sophia.signal.model.Category

/** Versioned, offline explanatory catalog. Entries are NOT signatures for identifying operators. */
data class ReferenceEntry(
    val category: Category,
    val applications: List<String>,
    val sectors: List<String>,
    val alternatives: String,
    val sourceIds: List<String>,
)

data class ReferenceSource(val id: String, val title: String, val url: String, val reviewed: String)

object ReferenceCatalog {
    const val VERSION = "2026-11-07.1"
    val sources = listOf(
        ReferenceSource("ANDROID_WIFI", "Android Wi-Fi ScanResult API", "https://developer.android.com/reference/android/net/wifi/ScanResult", "2026-11-07"),
        ReferenceSource("ANDROID_BLE", "Android Bluetooth LE ScanRecord API", "https://developer.android.com/reference/android/bluetooth/le/ScanRecord", "2026-11-07"),
        ReferenceSource("IEEE_OUI", "IEEE Registration Authority — public assignments", "https://standards.ieee.org/products-programs/regauth/", "2026-11-07"),
        ReferenceSource("BT_SIG", "Bluetooth SIG assigned numbers", "https://www.bluetooth.com/specifications/assigned-numbers/", "2026-11-07"),
    )
    val entries = listOf(
        ReferenceEntry(Category.ROUTER, listOf("Wireless LAN access", "Local network connectivity"), listOf("Homes", "Businesses", "Schools", "Public venues"), "An access point may be a phone hotspot or a virtual SSID; its operator is unknown.", listOf("ANDROID_WIFI", "IEEE_OUI")),
        ReferenceEntry(Category.INFRASTRUCTURE, listOf("Network connectivity", "Wireless distribution"), listOf("Businesses", "Public venues", "Residential properties"), "Network naming and vendor identifiers cannot establish whether infrastructure is public or privately operated.", listOf("ANDROID_WIFI", "IEEE_OUI")),
        ReferenceEntry(Category.CELLULAR, listOf("Mobile internet sharing", "Portable networking"), listOf("Personal use", "Business use"), "A hotspot-like network name does not establish that cellular backhaul is present.", listOf("ANDROID_WIFI")),
        ReferenceEntry(Category.COMMERCIAL, listOf("General wireless communications"), listOf("Consumer", "Commercial", "Industrial"), "A vendor makes multiple product lines for unrelated customers.", listOf("IEEE_OUI", "BT_SIG")),
        ReferenceEntry(Category.BT_ACCESSORY, listOf("Short-range accessory communication"), listOf("Personal", "Business", "Healthcare"), "An advertised company identifier may identify a protocol ecosystem rather than a hardware manufacturer.", listOf("ANDROID_BLE", "BT_SIG")),
        ReferenceEntry(Category.TRACKER, listOf("Proximity services", "Item-finding protocols"), listOf("Personal", "Inventory management"), "Similar advertisements can originate from unrelated products; they do not establish who possesses an item.", listOf("ANDROID_BLE", "BT_SIG")),
        ReferenceEntry(Category.BEACON, listOf("Proximity advertising", "Indoor information services"), listOf("Retail", "Facilities", "Museums"), "Beacon formats can be implemented by many different device types.", listOf("ANDROID_BLE", "BT_SIG")),
        ReferenceEntry(Category.UNKNOWN, listOf("Unclassified wireless emission"), listOf("Unknown"), "Insufficient observable evidence for hardware identification.", listOf("ANDROID_WIFI", "ANDROID_BLE")),
    ).associateBy { it.category }
    fun forCategory(category: Category): ReferenceEntry = entries.getValue(category)
    fun sourcesFor(category: Category): List<ReferenceSource> = forCategory(category).sourceIds.mapNotNull { id -> sources.find { it.id == id } }
}
