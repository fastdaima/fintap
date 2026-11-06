package com.fintap

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Money is stored as integer paise. Never floats.
class Expense(val id: Long, val name: String, val paise: Long, val ts: Long)

// All access happens on the main thread. The table is tiny (hundreds of rows/month),
// a range SUM over an indexed ts column is microseconds; a worker thread would cost more
// in complexity than it saves in time.
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "fintap.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE expense(" +
                "id INTEGER PRIMARY KEY, " +
                "name TEXT NOT NULL, " +
                "paise INTEGER NOT NULL CHECK(paise >= 0), " +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX expense_ts ON expense(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only. Add migrations here when the schema changes.
    }

    fun insert(name: String, paise: Long, ts: Long): Long {
        check(paise >= 0)
        val v = ContentValues(3)
        v.put("name", name)
        v.put("paise", paise)
        v.put("ts", ts)
        return writableDatabase.insertOrThrow("expense", null, v)
    }

    fun update(id: Long, name: String, paise: Long) {
        check(paise >= 0)
        val v = ContentValues(2)
        v.put("name", name)
        v.put("paise", paise)
        val n = writableDatabase.update("expense", v, "id=?", arrayOf(id.toString()))
        check(n == 1) { "update hit $n rows for id $id" }
    }

    fun delete(id: Long) {
        writableDatabase.delete("expense", "id=?", arrayOf(id.toString()))
    }

    // Returns [sumPaise, count] for ts in [from, to).
    fun total(from: Long, to: Long): LongArray {
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(paise), 0), COUNT(*) FROM expense WHERE ts >= ? AND ts < ?",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            c.moveToFirst()
            return longArrayOf(c.getLong(0), c.getLong(1))
        }
    }

    // Newest first, ts in [from, to).
    fun list(from: Long, to: Long): ArrayList<Expense> {
        val out = ArrayList<Expense>()
        readableDatabase.rawQuery(
            "SELECT id, name, paise, ts FROM expense WHERE ts >= ? AND ts < ? ORDER BY ts DESC, id DESC",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Expense(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3)))
            }
        }
        return out
    }

    companion object {
        private var instance: Db? = null

        fun get(ctx: Context): Db {
            var d = instance
            if (d == null) {
                d = Db(ctx.applicationContext)
                instance = d
            }
            return d
        }
    }
}
