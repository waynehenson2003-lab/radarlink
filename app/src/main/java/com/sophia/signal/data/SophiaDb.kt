package com.sophia.signal.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SophiaDb(ctx: Context) : SQLiteOpenHelper(ctx, "sophia.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started INTEGER NOT NULL,
                ended INTEGER,
                wifi INTEGER NOT NULL DEFAULT 0,
                ble INTEGER NOT NULL DEFAULT 0,
                note TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE idents(
                key TEXT PRIMARY KEY,
                radio TEXT NOT NULL,
                ident TEXT NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                sessions INTEGER NOT NULL,
                last_session INTEGER NOT NULL,
                flagged INTEGER NOT NULL DEFAULT 0,
                label TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE obs(
                session_id INTEGER NOT NULL,
                key TEXT NOT NULL,
                radio TEXT NOT NULL,
                ident TEXT NOT NULL,
                name TEXT,
                vendor TEXT,
                category TEXT NOT NULL,
                confidence INTEGER NOT NULL,
                priority INTEGER NOT NULL,
                prio_reason TEXT,
                rssi INTEGER NOT NULL,
                rssi_min INTEGER NOT NULL,
                rssi_max INTEGER NOT NULL,
                count INTEGER NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                freq INTEGER,
                channel INTEGER,
                security TEXT,
                meta TEXT NOT NULL,
                seen_before INTEGER NOT NULL,
                PRIMARY KEY(session_id, key))"""
        )
        db.execSQL("CREATE INDEX obs_key ON obs(key)")
        db.execSQL("CREATE TABLE samples(session_id INTEGER NOT NULL, key TEXT NOT NULL, ts INTEGER NOT NULL, rssi INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX samples_sk ON samples(session_id, key)")
        db.execSQL(
            """CREATE TABLE watch(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                value TEXT NOT NULL,
                label TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                created INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE hits(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                key TEXT NOT NULL,
                watch_id INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                rssi INTEGER NOT NULL,
                note TEXT)"""
        )
        db.execSQL("CREATE TABLE oui(prefix TEXT PRIMARY KEY, vendor TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

fun Cursor.str(c: String): String = getString(getColumnIndexOrThrow(c))
fun Cursor.strN(c: String): String? = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getString(it) }
fun Cursor.int(c: String): Int = getInt(getColumnIndexOrThrow(c))
fun Cursor.intN(c: String): Int? = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getInt(it) }
fun Cursor.long(c: String): Long = getLong(getColumnIndexOrThrow(c))
fun Cursor.longN(c: String): Long? = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getLong(it) }

inline fun <T> Cursor.mapAll(block: (Cursor) -> T): List<T> = use {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += block(this)
    out
}

inline fun SQLiteDatabase.tx(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
