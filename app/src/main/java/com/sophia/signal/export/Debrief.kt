package com.sophia.signal.export

import com.sophia.signal.data.Repository
import com.sophia.signal.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Debrief(
    val session: Session,
    val previous: Session?,
    val rows: List<ObsRow>,
    val wifi: Int,
    val ble: Int,
    val vendors: List<Pair<String, Int>>,
    val categories: List<Pair<Category, Int>>,
    val density: List<Pair<String, Int>>,
    val newIds: List<ObsRow>,
    val returning: List<ObsRow>,
    val appeared: List<ObsRow>,
    val disappeared: List<ObsRow>,
    val priority: List<ObsRow>,
    val unknown: List<ObsRow>,
    val hits: List<HitRow>,
    val generatedAt: Long,
)

object DebriefEngine {
    val BUCKETS = listOf("≥ -50 dBm (very strong)", "-51…-65 (strong)", "-66…-80 (moderate)", "< -80 (weak)")

    fun bucketOf(rssi: Int) = when {
        rssi >= -50 -> 0
        rssi >= -65 -> 1
        rssi >= -80 -> 2
        else -> 3
    }

    suspend fun build(repo: Repository, session: Session): Debrief {
        val rows = repo.sessionObs(session.id)
        val prev = repo.previousSession(session)
        val prevRows = prev?.let { repo.sessionObs(it.id) } ?: emptyList()
        val prevKeys = prevRows.map { it.key }.toSet()
        val keys = rows.map { it.key }.toSet()
        val density = IntArray(4).also { arr -> rows.forEach { arr[bucketOf(it.rssiMax)]++ } }
        return Debrief(
            session = session,
            previous = prev,
            rows = rows,
            wifi = rows.count { it.radio == Radio.WIFI },
            ble = rows.count { it.radio == Radio.BLE },
            vendors = rows.groupingBy { it.vendor ?: "Unidentified" }.eachCount().toList().sortedByDescending { it.second },
            categories = rows.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second },
            density = BUCKETS.mapIndexed { i, b -> b to density[i] },
            newIds = rows.filter { !it.seenBefore },
            returning = rows.filter { it.seenBefore },
            appeared = if (prev == null) emptyList() else rows.filter { it.key !in prevKeys },
            disappeared = prevRows.filter { it.key !in keys },
            priority = rows.filter { it.priority },
            unknown = rows.filter { it.category == Category.UNKNOWN },
            hits = repo.hits(session.id),
            generatedAt = System.currentTimeMillis(),
        )
    }
}

object Exporter {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private fun t(ms: Long) = iso.format(Date(ms))

    private fun esc(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun csv(rows: List<ObsRow>): String = buildString {
        appendLine(
            "session_id,radio,identifier,name,vendor,category,confidence,priority,priority_reason,rssi_last,rssi_min,rssi_max," +
                "sightings,first_seen,last_seen,frequency_mhz,channel,security,seen_in_earlier_session,metadata_json"
        )
        for (r in rows) {
            appendLine(
                listOf(
                    r.sessionId, r.radio.name, r.ident, r.name, r.vendor, r.category.name, r.confidence, r.priority,
                    r.priorityReason, r.rssi, r.rssiMin, r.rssiMax, r.count, t(r.firstSeen), t(r.lastSeen),
                    r.freq, r.channel, r.security, r.seenBefore, r.meta,
                ).joinToString(",") { esc(it) }
            )
        }
    }

    private fun rowJson(r: ObsRow) = JSONObject().apply {
        put("radio", r.radio.name); put("identifier", r.ident); put("name", r.name ?: JSONObject.NULL)
        put("vendor", r.vendor ?: JSONObject.NULL); put("category", r.category.name); put("confidence", r.confidence)
        put("priority", r.priority); put("priorityReason", r.priorityReason ?: JSONObject.NULL)
        put("rssi", JSONObject().apply { put("last", r.rssi); put("min", r.rssiMin); put("max", r.rssiMax) })
        put("sightings", r.count); put("firstSeen", t(r.firstSeen)); put("lastSeen", t(r.lastSeen))
        r.freq?.let { put("frequencyMHz", it) }; r.channel?.let { put("channel", it) }; r.security?.let { put("security", it) }
        put("seenInEarlierSession", r.seenBefore)
        put("metadata", runCatching { JSONObject(r.meta) }.getOrDefault(JSONObject()))
    }

    fun json(d: Debrief): String = JSONObject().apply {
        put("schema", "sophia.debrief.v1")
        put("generatedAt", t(d.generatedAt))
        put("session", JSONObject().apply {
            put("id", d.session.id); put("started", t(d.session.started))
            put("ended", d.session.ended?.let { t(it) } ?: JSONObject.NULL)
        })
        put("previousSessionId", d.previous?.id ?: JSONObject.NULL)
        put("totals", JSONObject().apply { put("wifi", d.wifi); put("ble", d.ble); put("all", d.rows.size) })
        put("manufacturerDistribution", JSONObject().apply { d.vendors.forEach { put(it.first, it.second) } })
        put("categoryDistribution", JSONObject().apply { d.categories.forEach { put(it.first.name, it.second) } })
        put("signalDensity", JSONObject().apply { d.density.forEach { put(it.first, it.second) } })
        put("newIdentifiers", JSONArray(d.newIds.map { it.ident }))
        put("previouslyObserved", JSONArray(d.returning.map { it.ident }))
        put("changesVsPrevious", JSONObject().apply {
            put("appeared", JSONArray(d.appeared.map { it.ident }))
            put("disappeared", JSONArray(d.disappeared.map { it.ident }))
        })
        put("priority", JSONArray(d.priority.map { rowJson(it) }))
        put("unknownHardware", JSONArray(d.unknown.map { it.ident }))
        put("watchlistMatches", JSONArray(d.hits.map { h ->
            JSONObject().apply { put("ts", t(h.ts)); put("key", h.key); put("rssi", h.rssi); put("note", h.note ?: JSONObject.NULL) }
        }))
        put("observations", JSONArray(d.rows.map { rowJson(it) }))
        put("limitations", JSONArray(LIMITATIONS))
    }.toString(2)

    val LIMITATIONS = listOf(
        "Observations are passive receptions by one Android phone; absence does not prove a device was not present.",
        "Wi-Fi results are subject to Android scan throttling (4 scans / 2 min in foreground).",
        "Many BLE devices rotate random addresses every ~15 min; one physical device can appear as several identifiers.",
        "Vendor attribution is based on OUI / company identifiers and naming heuristics; it identifies hardware makers, not owners.",
        "RSSI depends on orientation, obstacles and the phone's antenna; it is not a calibrated distance or location.",
    )

    /** Plain-language, structured report designed to be pasted into an AI assistant or read directly. */
    fun aiDebrief(d: Debrief): String = buildString {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val dur = ((d.session.ended ?: d.rows.maxOfOrNull { it.lastSeen } ?: d.session.started) - d.session.started) / 60_000
        appendLine("# SØPHIA ENVIRONMENTAL DEBRIEF")
        appendLine("Session #${d.session.id} · ${df.format(Date(d.session.started))} · ~${dur} min · generated ${df.format(Date(d.generatedAt))}")
        appendLine("Source: passive Wi-Fi beacon and BLE advertisement reception on a single Android device. No traffic captured.")
        appendLine()
        appendLine("## 1. Totals")
        appendLine("- Unique Wi-Fi identifiers: ${d.wifi}")
        appendLine("- Unique BLE identifiers: ${d.ble}")
        appendLine("- New (never seen in an earlier session): ${d.newIds.size}")
        appendLine("- Previously observed: ${d.returning.size}")
        appendLine()
        appendLine("## 2. Signal density (by strongest RSSI per identifier)")
        d.density.forEach { appendLine("- ${it.first}: ${it.second}") }
        appendLine()
        appendLine("## 3. Hardware categories")
        d.categories.forEach { appendLine("- ${it.first.label}: ${it.second}") }
        appendLine()
        appendLine("## 4. Manufacturer distribution (top 15)")
        d.vendors.take(15).forEach { appendLine("- ${it.first}: ${it.second}") }
        appendLine()
        appendLine("## 5. Changes vs previous session" + (d.previous?.let { " (#${it.id}, ${df.format(Date(it.started))})" } ?: ""))
        if (d.previous == null) appendLine("- No earlier session to compare.")
        else {
            appendLine("- Appeared: ${d.appeared.size}")
            d.appeared.sortedByDescending { it.rssiMax }.take(10).forEach { appendLine("  + ${line(it)}") }
            appendLine("- Disappeared: ${d.disappeared.size}")
            d.disappeared.sortedByDescending { it.rssiMax }.take(10).forEach { appendLine("  - ${line(it)}") }
        }
        appendLine()
        appendLine("## 6. Priority classifications (${d.priority.size})")
        if (d.priority.isEmpty()) appendLine("- None.")
        d.priority.sortedByDescending { it.rssiMax }.forEach { appendLine("- ${line(it)} — ${it.priorityReason ?: "rule"}") }
        appendLine()
        appendLine("## 7. Watchlist matches (${d.hits.size})")
        if (d.hits.isEmpty()) appendLine("- None.")
        d.hits.take(25).forEach { appendLine("- ${df.format(Date(it.ts))} ${it.key} ${it.rssi} dBm (${it.note ?: "watch"})") }
        appendLine()
        appendLine("## 8. Unknown hardware (${d.unknown.size}, strongest 15)")
        d.unknown.sortedByDescending { it.rssiMax }.take(15).forEach { appendLine("- ${line(it)}") }
        appendLine()
        appendLine("## 9. Interpretation limits")
        LIMITATIONS.forEach { appendLine("- $it") }
    }

    private fun line(r: ObsRow) =
        "[${r.radio.label}] ${r.name ?: "(no name)"} ${r.ident} · ${r.vendor ?: "unidentified"} · ${r.category.short} ${r.confidence}% · max ${r.rssiMax} dBm · ${r.count}×"
}
