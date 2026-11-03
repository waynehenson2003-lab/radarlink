package com.sophia.signal.classify

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.sophia.signal.data.SophiaDb
import com.sophia.signal.data.mapAll
import com.sophia.signal.data.str
import com.sophia.signal.data.tx

class OuiRegistry(private val db: SophiaDb) {
    @Volatile private var imported: Map<String, String> = emptyMap()

    val importedCount: Int get() = imported.size
    val builtinCount: Int get() = BuiltinOui.MAP.size

    fun load() {
        imported = db.readableDatabase.rawQuery("SELECT prefix, vendor FROM oui", null)
            .mapAll { it.str("prefix") to it.str("vendor") }
            .toMap()
    }

    /** Returns (vendor, source) or null. */
    fun lookup(mac: String): Pair<String, String>? {
        val p = mac.replace(":", "").replace("-", "").uppercase().take(6)
        if (p.length < 6) return null
        imported[p]?.let { return it to "IEEE registry (imported)" }
        BuiltinOui.MAP[p]?.let { return it to "built-in seed table" }
        return null
    }

    /**
     * Accepts the IEEE oui.csv (Registry,Assignment,Organization Name,...) or simple
     * "AABBCC,Vendor" lines. Returns the number of entries stored.
     */
    fun import(lines: Sequence<String>): Int {
        val hex6 = Regex("^[0-9A-Fa-f]{6}$")
        val entries = HashMap<String, String>()
        for (line in lines) {
            val f = parseCsvLine(line)
            val idx = f.indexOfFirst { hex6.matches(it.trim()) }
            if (idx < 0 || idx + 1 >= f.size) continue
            val vendor = f[idx + 1].trim()
            if (vendor.isEmpty()) continue
            entries[f[idx].trim().uppercase()] = vendor
        }
        if (entries.isEmpty()) return 0
        db.writableDatabase.tx {
            entries.forEach { (k, v) ->
                insertWithOnConflict("oui", null, ContentValues().apply { put("prefix", k); put("vendor", v) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        load()
        return entries.size
    }

    fun clearImported() {
        db.writableDatabase.delete("oui", null, null)
        load()
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQ && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
