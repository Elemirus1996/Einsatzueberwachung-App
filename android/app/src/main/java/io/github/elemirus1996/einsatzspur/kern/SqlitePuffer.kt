package io.github.elemirus1996.einsatzspur.kern

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Funkloch-Puffer in SQLite (App-eigener Speicher, nicht gesichert). */
class SqlitePuffer(context: Context) :
    SQLiteOpenHelper(context, "positionen.db", null, 1), PositionsSpeicher {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE positionen (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL, lng REAL NOT NULL, accuracy REAL,
                timestamp INTEGER NOT NULL, battery INTEGER)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, alt: Int, neu: Int) = Unit

    override fun hinzufuegen(position: Position) {
        writableDatabase.insert("positionen", null, ContentValues().apply {
            put("lat", position.lat)
            put("lng", position.lng)
            put("accuracy", position.accuracy)
            put("timestamp", position.timestamp)
            put("battery", position.battery)
        })
    }

    override fun aelteste(anzahl: Int): List<Pair<Long, Position>> =
        readableDatabase.query(
            "positionen", arrayOf("id", "lat", "lng", "accuracy", "timestamp", "battery"),
            null, null, null, null, "id", anzahl.toString()
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        c.getLong(0) to Position(
                            lat = c.getDouble(1),
                            lng = c.getDouble(2),
                            accuracy = if (c.isNull(3)) null else c.getDouble(3),
                            timestamp = c.getLong(4),
                            battery = if (c.isNull(5)) null else c.getInt(5),
                        )
                    )
                }
            }
        }

    override fun loeschenBis(id: Long) {
        writableDatabase.delete("positionen", "id <= ?", arrayOf(id.toString()))
    }

    override fun anzahl(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM positionen", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun leeren() {
        writableDatabase.delete("positionen", null, null)
    }
}
