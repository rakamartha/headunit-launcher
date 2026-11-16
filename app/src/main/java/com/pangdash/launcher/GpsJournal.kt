package com.pangdash.launcher

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/** Journal lokal: fix baru hanya dibuang setelah hasil perhitungan tersimpan atomik. */
class GpsJournal private constructor(context: Context) : SQLiteOpenHelper(context, "gps-journal.db", null, 1) {
    companion object {
        @Volatile private var instance: GpsJournal? = null
        fun get(context: Context): GpsJournal = instance ?: synchronized(this) {
            instance ?: GpsJournal(context.applicationContext).also { instance = it }
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fixes (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE checkpoint (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun append(payload: String) {
        writableDatabase.insertOrThrow("fixes", null, ContentValues().apply { put("payload", payload) })
    }
    @Synchronized fun batch(): String {
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT id, payload FROM fixes ORDER BY id LIMIT 120", null).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(org.json.JSONObject(cursor.getString(1)).put("id", cursor.getLong(0)))
            }
        }
        return result.toString()
    }
    @Synchronized fun checkpoint(): String = readableDatabase.rawQuery(
        "SELECT payload FROM checkpoint WHERE id=1", null
    ).use { if (it.moveToFirst()) it.getString(0) else "" }

    @Synchronized fun commit(throughId: Long, snapshot: String): Boolean {
        if (throughId < 0 || snapshot.length > 2_000_000) return false
        org.json.JSONObject(snapshot) // Tolak checkpoint yang tidak utuh.
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.insertWithOnConflict("checkpoint", null, ContentValues().apply {
                put("id", 1); put("payload", snapshot)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            check(stored != -1L) { "Checkpoint GPS gagal disimpan" }
            db.delete("fixes", "id<=?", arrayOf(throughId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }
}
