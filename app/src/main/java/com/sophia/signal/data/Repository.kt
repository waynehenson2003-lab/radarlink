package com.sophia.signal.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.sophia.signal.alert.Alerts
import com.sophia.signal.classify.Classifier
import com.sophia.signal.classify.OuiRegistry
import com.sophia.signal.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Single source of truth. All database work runs on one dedicated thread so writes are
 * strictly ordered and the UI thread never touches SQLite.
 */
class Repository(private val db: SophiaDb, private val prefs: Prefs, private val alerts: Alerts) {

    private val io = Executors.newSingleThreadExecutor { Thread(it, "sophia-data") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    val oui = OuiRegistry(db)

    private val idents = HashMap<String, IdentRecord>()
    private val lastSample = HashMap<String, Pair<Long, Int>>()
    private val lastAlert = HashMap<String, Long>()

    private val _live = MutableStateFlow<Map<String, Observation>>(emptyMap())
    val live: StateFlow<Map<String, Observation>> = _live.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _events = MutableStateFlow<List<PriorityEvent>>(emptyList())
    val events: StateFlow<List<PriorityEvent>> = _events.asStateFlow()

    private val _watch = MutableStateFlow<List<WatchRule>>(emptyList())
    val watch: StateFlow<List<WatchRule>> = _watch.asStateFlow()

    private val _flagged = MutableStateFlow<List<IdentRecord>>(emptyList())
    val flagged: StateFlow<List<IdentRecord>> = _flagged.asStateFlow()

    /** Bumped whenever stored history changes (session start/end, deletions, imports). */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _ouiCount = MutableStateFlow(0)
    val ouiImportedCount: StateFlow<Int> = _ouiCount.asStateFlow()

    init {
        scope.launch {
            closeDanglingSessions()
            loadIdents()
            loadWatch()
            oui.load()
            _ouiCount.value = oui.importedCount
            purgeInternal(prefs.settings.value.retentionDays)
            bump()
        }
    }

    private fun bump() { _version.value = _version.value + 1 }
    private val w: SQLiteDatabase get() = db.writableDatabase

    // ---------------------------------------------------------------- sessions

    fun ensureSession() = scope.launch {
        if (_session.value != null) return@launch
        val now = System.currentTimeMillis()
        val id = w.insert("sessions", null, ContentValues().apply { put("started", now) })
        _live.value = emptyMap()
        lastSample.clear()
        _session.value = Session(id, now, null, 0, 0)
        bump()
    }

    fun endSession() = scope.launch {
        val s = _session.value ?: return@launch
        val live = _live.value.values
        if (live.isEmpty()) {
            w.delete("sessions", "id=?", arrayOf(s.id.toString()))
        } else {
            w.update("sessions", ContentValues().apply {
                put("ended", System.currentTimeMillis())
                put("wifi", live.count { it.radio == Radio.WIFI })
                put("ble", live.count { it.radio == Radio.BLE })
            }, "id=?", arrayOf(s.id.toString()))
        }
        _session.value = null
        bump()
    }

    private fun closeDanglingSessions() {
        w.execSQL(
            """UPDATE sessions SET ended = COALESCE((SELECT MAX(last_seen) FROM obs WHERE obs.session_id = sessions.id), started)
               WHERE ended IS NULL"""
        )
        w.execSQL("DELETE FROM sessions WHERE id NOT IN (SELECT DISTINCT session_id FROM obs)")
    }

    fun clearLive(radio: Radio) = scope.launch {
        _live.value = _live.value.filterValues { it.radio != radio }
    }

    // ---------------------------------------------------------------- ingest

    fun ingest(batch: List<RawSighting>) {
        if (batch.isEmpty()) return
        scope.launch { process(batch) }
    }

    private fun process(batch: List<RawSighting>) {
        val s = _session.value ?: return
        val st = prefs.settings.value
        val now = System.currentTimeMillis()
        val cooldown = st.cooldownMin * 60_000L
        val live = _live.value.toMutableMap()
        val rules = _watch.value.filter { it.enabled }
        val touched = ArrayList<Observation>()
        val samples = ArrayList<Triple<String, Long, Int>>()
        val hitsOut = ArrayList<ContentValues>()
        val newEvents = ArrayList<PriorityEvent>()
        val touchedIdents = HashSet<String>()

        for (raw in batch) {
            if (raw.rssi < st.rssiFloor) continue
            val key = "${raw.radio.name}:${raw.id}"
            val prev = live[key]
            var ident = idents[key]
            var seenBefore = prev?.seenBefore ?: false
            var prior = prev?.priorSessions ?: 0
            if (prev == null) {
                if (ident == null) {
                    ident = IdentRecord(key, raw.radio, raw.id, raw.ts, raw.ts, 1, s.id, false, null)
                    idents[key] = ident
                } else if (ident.lastSession != s.id) {
                    seenBefore = true
                    prior = ident.sessions
                    ident.sessions += 1
                    ident.lastSession = s.id
                } else {
                    seenBefore = ident.sessions > 1
                    prior = ident.sessions - 1
                }
            }
            val id = ident!!
            id.lastSeen = raw.ts
            touchedIdents += key

            val cls = Classifier.classify(raw, oui, st)
            val rule = rules.firstOrNull { matches(it, raw, cls) }
            val watchLabel = when {
                rule != null -> rule.label?.takeIf { it.isNotBlank() } ?: "${rule.type.name}: ${rule.value}"
                id.flagged -> "Flagged identifier"
                else -> null
            }

            val obs = Observation(
                key = key, radio = raw.radio, id = raw.id,
                name = raw.name ?: prev?.name,
                rssi = raw.rssi,
                rssiMin = min(prev?.rssiMin ?: raw.rssi, raw.rssi),
                rssiMax = max(prev?.rssiMax ?: raw.rssi, raw.rssi),
                rssiPrev = prev?.rssi,
                firstSeen = prev?.firstSeen ?: raw.ts,
                lastSeen = raw.ts,
                count = (prev?.count ?: 0) + 1,
                raw = raw, cls = cls,
                seenBefore = seenBefore, priorSessions = prior,
                flagged = id.flagged, label = id.label, watchHit = watchLabel,
            )
            live[key] = obs
            touched += obs

            val ls = lastSample[key]
            if (ls == null || raw.ts - ls.first >= 15_000 || abs(raw.rssi - ls.second) >= 4) {
                samples += Triple(key, raw.ts, raw.rssi)
                lastSample[key] = raw.ts to raw.rssi
            }

            // Reappearance alerts: first sighting this session, or absent longer than the cooldown.
            val absentLongEnough = prev == null || now - prev.lastSeen > cooldown
            val notRecentlyAlerted = now - (lastAlert[key] ?: 0L) > cooldown
            val display = obs.label ?: obs.name?.takeIf { it.isNotBlank() } ?: obs.id
            if (watchLabel != null && absentLongEnough && notRecentlyAlerted) {
                lastAlert[key] = now
                val detail = "${raw.radio.label} $display · ${raw.rssi} dBm · ${cls.category.label}"
                newEvents += PriorityEvent(now, key, "Watch match: $watchLabel", detail, true)
                hitsOut += ContentValues().apply {
                    put("session_id", s.id); put("key", key); put("watch_id", rule?.id ?: -1L)
                    put("ts", now); put("rssi", raw.rssi); put("note", LocalCrypto.enc(watchLabel))
                }
                alerts.alert("Watch match: $watchLabel", detail)
            } else if (cls.priority && prev == null) {
                val detail = "${raw.radio.label} $display · ${raw.rssi} dBm · ${cls.priorityReason}"
                newEvents += PriorityEvent(now, key, "Priority: ${cls.category.label}", detail, false)
                if (st.alertOnPriority && notRecentlyAlerted) {
                    lastAlert[key] = now
                    alerts.alert("Priority: ${cls.category.label}", detail)
                }
            }
        }

        if (touched.isEmpty()) return

        w.tx {
            for (o in touched) insertWithOnConflict("obs", null, obsValues(s.id, o), SQLiteDatabase.CONFLICT_REPLACE)
            for ((k, ts, r) in samples) insert("samples", null, ContentValues().apply {
                put("session_id", s.id); put("key", k); put("ts", ts); put("rssi", r)
            })
            for (k in touchedIdents) idents[k]?.let { insertWithOnConflict("idents", null, identValues(it), SQLiteDatabase.CONFLICT_REPLACE) }
            for (h in hitsOut) insert("hits", null, h)
            val wifi = live.values.count { it.radio == Radio.WIFI }
            val ble = live.values.count { it.radio == Radio.BLE }
            update("sessions", ContentValues().apply { put("wifi", wifi); put("ble", ble) }, "id=?", arrayOf(s.id.toString()))
            _session.value = s.copy(wifi = wifi, ble = ble)
        }

        _live.value = live
        if (newEvents.isNotEmpty()) _events.value = (newEvents.reversed() + _events.value).take(200)
    }

    private fun matches(r: WatchRule, raw: RawSighting, cls: Classification): Boolean {
        val v = r.value.trim()
        if (v.isEmpty()) return false
        return when (r.type) {
            WatchType.EXACT_ID -> raw.id.equals(v, ignoreCase = true)
            WatchType.SSID_CONTAINS -> raw.name?.contains(v, ignoreCase = true) == true
            WatchType.VENDOR_CONTAINS -> cls.vendor?.contains(v, ignoreCase = true) == true
            WatchType.BLE_COMPANY -> v.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.let { it in raw.companyIds } == true
            WatchType.CATEGORY -> cls.category.name.equals(v, ignoreCase = true) || cls.category.label.contains(v, ignoreCase = true)
        }
    }

    private fun obsValues(sessionId: Long, o: Observation) = ContentValues().apply {
        put("session_id", sessionId); put("key", o.key); put("radio", o.radio.name); put("ident", o.id)
        put("name", o.name); put("vendor", o.cls.vendor); put("category", o.cls.category.name)
        put("confidence", o.cls.confidence); put("priority", if (o.cls.priority) 1 else 0)
        put("prio_reason", o.cls.priorityReason)
        put("rssi", o.rssi); put("rssi_min", o.rssiMin); put("rssi_max", o.rssiMax); put("count", o.count)
        put("first_seen", o.firstSeen); put("last_seen", o.lastSeen)
        put("freq", o.raw.frequency); put("channel", o.raw.channel); put("security", o.raw.security)
        put("meta", metaJson(o).toString()); put("seen_before", if (o.seenBefore) 1 else 0)
    }

    private fun identValues(r: IdentRecord) = ContentValues().apply {
        put("key", r.key); put("radio", r.radio.name); put("ident", r.ident)
        put("first_seen", r.firstSeen); put("last_seen", r.lastSeen); put("sessions", r.sessions)
        put("last_session", r.lastSession); put("flagged", if (r.flagged) 1 else 0)
        put("label", LocalCrypto.enc(r.label))
    }

    // ---------------------------------------------------------------- identifiers

    private fun loadIdents() {
        idents.clear()
        db.readableDatabase.rawQuery("SELECT * FROM idents", null).mapAll {
            IdentRecord(
                it.str("key"), Radio.valueOf(it.str("radio")), it.str("ident"), it.long("first_seen"),
                it.long("last_seen"), it.int("sessions"), it.long("last_session"), it.int("flagged") == 1,
                LocalCrypto.dec(it.strN("label")),
            )
        }.forEach { idents[it.key] = it }
        refreshFlagged()
    }

    private fun refreshFlagged() {
        _flagged.value = idents.values.filter { it.flagged }.sortedByDescending { it.lastSeen }
    }

    fun setFlag(key: String, flagged: Boolean) = scope.launch {
        val r = idents[key] ?: return@launch
        r.flagged = flagged
        w.insertWithOnConflict("idents", null, identValues(r), SQLiteDatabase.CONFLICT_REPLACE)
        _live.value[key]?.let { o -> _live.value = _live.value + (key to o.copy(flagged = flagged)) }
        refreshFlagged()
    }

    fun setLabel(key: String, label: String?) = scope.launch {
        val r = idents[key] ?: return@launch
        r.label = label?.takeIf { it.isNotBlank() }
        w.insertWithOnConflict("idents", null, identValues(r), SQLiteDatabase.CONFLICT_REPLACE)
        _live.value[key]?.let { o -> _live.value = _live.value + (key to o.copy(label = r.label)) }
        refreshFlagged()
    }

    fun ident(key: String): IdentRecord? = idents[key]

    // ---------------------------------------------------------------- watchlist

    private fun loadWatch() {
        _watch.value = db.readableDatabase.rawQuery("SELECT * FROM watch ORDER BY created DESC", null).mapAll {
            WatchRule(
                it.long("id"), WatchType.valueOf(it.str("type")), LocalCrypto.dec(it.str("value")) ?: "",
                LocalCrypto.dec(it.strN("label")), it.int("enabled") == 1, it.long("created"),
            )
        }
    }

    fun addWatch(type: WatchType, value: String, label: String?) = scope.launch {
        w.insert("watch", null, ContentValues().apply {
            put("type", type.name); put("value", LocalCrypto.enc(value.trim()))
            put("label", LocalCrypto.enc(label?.trim()?.takeIf { it.isNotEmpty() }))
            put("enabled", 1); put("created", System.currentTimeMillis())
        })
        loadWatch()
    }

    fun setWatchEnabled(id: Long, enabled: Boolean) = scope.launch {
        w.update("watch", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id.toString()))
        loadWatch()
    }

    fun deleteWatch(id: Long) = scope.launch {
        w.delete("watch", "id=?", arrayOf(id.toString()))
        loadWatch()
    }

    // ---------------------------------------------------------------- history queries

    suspend fun sessions(): List<Session> = withContext(io) {
        db.readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY started DESC", null).mapAll {
            Session(it.long("id"), it.long("started"), it.longN("ended"), it.int("wifi"), it.int("ble"))
        }
    }

    suspend fun sessionObs(sessionId: Long): List<ObsRow> = withContext(io) {
        db.readableDatabase.rawQuery("SELECT * FROM obs WHERE session_id=? ORDER BY rssi_max DESC", arrayOf(sessionId.toString()))
            .mapAll { rowOf(it) }
    }

    suspend fun identHistory(key: String): List<ObsRow> = withContext(io) {
        db.readableDatabase.rawQuery("SELECT * FROM obs WHERE key=? ORDER BY first_seen DESC LIMIT 100", arrayOf(key)).mapAll { rowOf(it) }
    }

    suspend fun samples(sessionId: Long, key: String): List<Pair<Long, Int>> = withContext(io) {
        db.readableDatabase.rawQuery(
            "SELECT ts, rssi FROM samples WHERE session_id=? AND key=? ORDER BY ts", arrayOf(sessionId.toString(), key)
        ).mapAll { it.long("ts") to it.int("rssi") }
    }

    suspend fun search(q: String, radio: Radio?, priorityOnly: Boolean): List<ObsRow> = withContext(io) {
        val where = StringBuilder("(name LIKE ? OR ident LIKE ? OR vendor LIKE ? OR category LIKE ?)")
        val like = "%${q.trim()}%"
        val args = arrayListOf(like, like, like, like)
        if (radio != null) { where.append(" AND radio=?"); args += radio.name }
        if (priorityOnly) where.append(" AND priority=1")
        db.readableDatabase.rawQuery("SELECT * FROM obs WHERE $where ORDER BY last_seen DESC LIMIT 400", args.toTypedArray())
            .mapAll { rowOf(it) }
    }

    suspend fun hits(sessionId: Long? = null, limit: Int = 200): List<HitRow> = withContext(io) {
        val (sql, args) = if (sessionId != null) "SELECT * FROM hits WHERE session_id=? ORDER BY ts DESC LIMIT $limit" to arrayOf(sessionId.toString())
        else "SELECT * FROM hits ORDER BY ts DESC LIMIT $limit" to emptyArray<String>()
        db.readableDatabase.rawQuery(sql, args).mapAll {
            HitRow(it.long("ts"), it.long("session_id"), it.str("key"), it.long("watch_id"), it.int("rssi"), LocalCrypto.dec(it.strN("note")))
        }
    }

    suspend fun compare(a: Session, b: Session): SessionCompare {
        val ra = sessionObs(a.id).associateBy { it.key }
        val rb = sessionObs(b.id).associateBy { it.key }
        return SessionCompare(
            a, b,
            onlyA = ra.values.filter { it.key !in rb },
            onlyB = rb.values.filter { it.key !in ra },
            both = ra.values.mapNotNull { x -> rb[x.key]?.let { x to it } },
        )
    }

    suspend fun previousSession(s: Session): Session? = sessions().filter { it.started < s.started }.maxByOrNull { it.started }

    // ---------------------------------------------------------------- deletion / retention

    fun deleteSession(id: Long, done: () -> Unit = {}) = scope.launch {
        if (_session.value?.id == id) return@launch
        w.tx {
            delete("obs", "session_id=?", arrayOf(id.toString()))
            delete("samples", "session_id=?", arrayOf(id.toString()))
            delete("hits", "session_id=?", arrayOf(id.toString()))
            delete("sessions", "id=?", arrayOf(id.toString()))
            recountIdents()
        }
        loadIdents(); bump(); done()
    }

    fun deleteAllHistory(done: () -> Unit = {}) = scope.launch {
        val active = _session.value?.id ?: -1L
        w.tx {
            delete("obs", "session_id<>?", arrayOf(active.toString()))
            delete("samples", "session_id<>?", arrayOf(active.toString()))
            delete("hits", "session_id<>?", arrayOf(active.toString()))
            delete("sessions", "id<>?", arrayOf(active.toString()))
            recountIdents()
        }
        loadIdents(); _events.value = emptyList(); bump(); done()
    }

    fun purge(days: Int, done: (Int) -> Unit = {}) = scope.launch {
        val n = purgeInternal(days); loadIdents(); bump(); done(n)
    }

    private fun purgeInternal(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val active = _session.value?.id ?: -1L
        val ids = db.readableDatabase.rawQuery("SELECT id FROM sessions WHERE started < ? AND id <> ?", arrayOf(cutoff.toString(), active.toString()))
            .mapAll { it.long("id") }
        if (ids.isEmpty()) return 0
        w.tx {
            for (id in ids) {
                delete("obs", "session_id=?", arrayOf(id.toString()))
                delete("samples", "session_id=?", arrayOf(id.toString()))
                delete("hits", "session_id=?", arrayOf(id.toString()))
                delete("sessions", "id=?", arrayOf(id.toString()))
            }
            recountIdents()
        }
        return ids.size
    }

    private fun SQLiteDatabase.recountIdents() {
        execSQL("UPDATE idents SET sessions = (SELECT COUNT(*) FROM obs WHERE obs.key = idents.key)")
        execSQL("DELETE FROM idents WHERE sessions = 0 AND flagged = 0 AND label IS NULL")
    }

    // ---------------------------------------------------------------- OUI

    fun importOui(lines: Sequence<String>, done: (Int) -> Unit) = scope.launch {
        val n = try { oui.import(lines) } catch (e: Exception) { -1 }
        _ouiCount.value = oui.importedCount
        done(n)
    }

    fun clearOui() = scope.launch { oui.clearImported(); _ouiCount.value = 0 }

    // ---------------------------------------------------------------- helpers

    private fun rowOf(c: android.database.Cursor) = ObsRow(
        sessionId = c.long("session_id"), key = c.str("key"), radio = Radio.valueOf(c.str("radio")),
        ident = c.str("ident"), name = c.strN("name"), vendor = c.strN("vendor"),
        category = runCatching { Category.valueOf(c.str("category")) }.getOrDefault(Category.UNKNOWN),
        confidence = c.int("confidence"), priority = c.int("priority") == 1, priorityReason = c.strN("prio_reason"),
        rssi = c.int("rssi"), rssiMin = c.int("rssi_min"), rssiMax = c.int("rssi_max"), count = c.int("count"),
        firstSeen = c.long("first_seen"), lastSeen = c.long("last_seen"), freq = c.intN("freq"),
        channel = c.intN("channel"), security = c.strN("security"), meta = c.str("meta"),
        seenBefore = c.int("seen_before") == 1,
    )

    companion object {
        fun metaJson(o: Observation): JSONObject = JSONObject().apply {
            val r = o.raw
            put("basis", JSONArray(o.cls.basis))
            r.band?.let { put("band", it) }
            r.channelWidth?.let { put("channelWidth", it) }
            r.standard?.let { put("standard", it) }
            r.capabilities?.let { put("capabilities", it) }
            if (r.companyIds.isNotEmpty()) put("companyIds", JSONArray(r.companyIds.map { "0x%04X".format(it) }))
            if (r.mfgHex.isNotEmpty()) put("manufacturerData", JSONObject().apply { r.mfgHex.forEach { (k, v) -> put("0x%04X".format(k), v) } })
            if (r.serviceUuids.isNotEmpty()) put("serviceUuids", JSONArray(r.serviceUuids))
            r.txPower?.let { put("txPower", it) }
            r.connectable?.let { put("connectable", it) }
            r.appleType?.let { put("appleType", "0x%02X".format(it)) }
            r.beaconInfo?.let { put("beacon", it) }
            put("fingerprint", r.fingerprint)
        }

        fun toRow(sessionId: Long, o: Observation) = ObsRow(
            sessionId, o.key, o.radio, o.id, o.name, o.cls.vendor, o.cls.category, o.cls.confidence,
            o.cls.priority, o.cls.priorityReason, o.rssi, o.rssiMin, o.rssiMax, o.count, o.firstSeen,
            o.lastSeen, o.raw.frequency, o.raw.channel, o.raw.security, metaJson(o).toString(), o.seenBefore,
        )
    }
}
