package com.sophia.signal.classify

/**
 * A small built-in seed of well-known OUIs so classification works offline on first launch.
 * It is intentionally partial. For full coverage, import the IEEE MA-L registry (oui.csv)
 * from Settings → OUI database; imported entries take precedence over this table.
 */
object BuiltinOui {
    private val RAW = mapOf(
        "Google" to "001A11 3C5AB4 F4F5D8 F88FCA",
        "Apple" to "000393 000A95 0017F2 001B63 001CB3 28CFE9 3C0754 7CD1C3 A4B197",
        "Microsoft" to "0050F2 001DD8",
        "Raspberry Pi" to "B827EB DCA632 E45F01 2CCF67 D83ADD",
        "Espressif" to "240AC4 30AEA4 246F28 A4CF12 84F3EB 5CCF7F 18FE34 600194 3C71BF 7C9EBD AC67B2",
        "TP-Link" to "14CC20 50C7BF 001D0F F81A67",
        "Netgear" to "000FB5 00146C 001E2A 00095B 001B2F 00223F 0024B2 204E7F 30469A 04A151",
        "Cisco-Linksys" to "001310 001839 001A70 002129 00259C 586D8F C0C1C0 000C41 001D7E",
        "D-Link" to "001E58 002401 00265A 000D88 001195 0015E9 00179A 00195B 1C7EE5 001346",
        "AVM" to "001F3F 00040E 246511 3810D5 7CFF4D C02506",
        "Ubiquiti" to "24A43C 0418D6 802AA8 F09FC2 788A20 18E829 7483C2 E063DA DC9FDB 687251",
        "Cisco Meraki" to "00180A E0553D AC17C8",
        "Aruba Networks" to "000B86 001A1E 186472 24DEC6",
        "Cisco Systems" to "00000C",
        "Ruckus Wireless" to "001F41 C4017C",
        "MikroTik" to "4C5E0C 6C3B6B D4CA6D E48D8C",
        "Sierra Wireless" to "00A0D5",
        "Cradlepoint" to "003044",
        "Novatel Wireless" to "0015FF",
        "Huawei" to "00E0FC 001882 001E10 00259E 286ED4 4846FB 548998",
        "ZTE" to "0015EB 0019C6 001E73 986CF5",
        "Amazon" to "74C246 44650D F0272D 6837E9 0C47C9 FC65DE 84D6D0",
        "Roku" to "B0A737 DC3A5E CC6DA0 AC3A7A",
        "Samsung Electronics" to "0000F0 001247 001599 001632 001D25 8C7712",
        "Sonos" to "000E58 5CAAFD 7828CA 949F3E B8E937",
        "Hikvision" to "4419B6 C056E3 BCAD28",
        "Intel" to "001B21 001E67 3CA9F4",
        "Nintendo" to "0009BF 0017AB 001F32 98B6E9",
        "Belkin" to "001150 94103E EC1A59",
        "Synology" to "001132",
        "VMware" to "000C29 005056",
        "Realtek" to "00E04C",
    )

    val MAP: Map<String, String> = buildMap {
        RAW.forEach { (vendor, prefixes) -> prefixes.split(" ").forEach { put(it, vendor) } }
    }
}

/** Maps a vendor name (built-in or IEEE) to a hardware category by keyword. */
object Vendors {
    private val CELLULAR = listOf(
        "sierra wireless", "cradlepoint", "novatel", "inseego", "franklin wireless", "quectel",
        "telit", "huawei", "zte", "netgear mobile", "digi international", "teltonika", "peplink",
    )
    private val ROUTER = listOf(
        "tp-link", "netgear", "linksys", "d-link", "asustek", "avm", "belkin", "arris", "technicolor",
        "sagemcom", "zyxel", "eero", "tenda", "actiontec", "calix", "askey", "sercomm", "commscope",
        "nokia shanghai bell", "mercusys", "gl technologies",
    )
    private val INFRA = listOf(
        "cisco", "meraki", "aruba", "ubiquiti", "ruckus", "juniper", "extreme networks", "fortinet",
        "cambium", "mikrotik", "mist systems", "hewlett packard enterprise", "aerohive",
    )
    private val ACCESSORY = listOf("bose", "sony", "garmin", "logitech", "huami", "ruuvi", "jabra", "gn audio", "sennheiser", "jbl", "harman")

    fun categoryOf(vendor: String): com.sophia.signal.model.Category {
        val v = vendor.lowercase()
        return when {
            CELLULAR.any { v.contains(it) } -> com.sophia.signal.model.Category.CELLULAR
            INFRA.any { v.contains(it) } -> com.sophia.signal.model.Category.INFRASTRUCTURE
            ROUTER.any { v.contains(it) } -> com.sophia.signal.model.Category.ROUTER
            ACCESSORY.any { v.contains(it) } -> com.sophia.signal.model.Category.BT_ACCESSORY
            else -> com.sophia.signal.model.Category.COMMERCIAL
        }
    }
}

/** Bluetooth SIG assigned company identifiers (subset) used in manufacturer-specific data. */
object BleCompanies {
    val MAP: Map<Int, String> = mapOf(
        0x0002 to "Intel",
        0x0006 to "Microsoft",
        0x000A to "Qualcomm Technologies Intl",
        0x000D to "Texas Instruments",
        0x000F to "Broadcom",
        0x001D to "Qualcomm",
        0x0030 to "ST Microelectronics",
        0x0046 to "MediaTek",
        0x004C to "Apple",
        0x0059 to "Nordic Semiconductor",
        0x0075 to "Samsung Electronics",
        0x0087 to "Garmin",
        0x009E to "Bose",
        0x00C4 to "LG Electronics",
        0x00E0 to "Google",
        0x0118 to "Radius Networks",
        0x012D to "Sony",
        0x0131 to "Cypress Semiconductor",
        0x0157 to "Anhui Huami",
        0x0171 to "Amazon",
        0x01DA to "Logitech",
        0x027D to "Huawei",
        0x02E5 to "Espressif",
        0x038F to "Xiaomi",
        0x0499 to "Ruuvi Innovations",
    )
}

/** 16-bit service UUIDs we recognize, keyed by uppercase short form. */
object BleServices {
    val MAP: Map<String, String> = mapOf(
        "FEAA" to "Google Eddystone",
        "FD6F" to "Exposure Notification",
        "FEED" to "Tile",
        "FEEC" to "Tile",
        "FD5A" to "Samsung SmartThings Find",
        "FE9F" to "Google",
        "180D" to "Heart Rate",
        "1816" to "Cycling Speed & Cadence",
        "1818" to "Cycling Power",
        "1826" to "Fitness Machine",
        "1812" to "Human Interface Device",
        "180F" to "Battery Service",
        "181A" to "Environmental Sensing",
        "1809" to "Health Thermometer",
        "1810" to "Blood Pressure",
        "180A" to "Device Information",
    )

    fun short(uuid: String): String {
        val u = uuid.lowercase()
        return if (u.length == 36 && u.endsWith("-0000-1000-8000-00805f9b34fb") && u.startsWith("0000")) {
            u.substring(4, 8).uppercase()
        } else uuid.uppercase()
    }
}
