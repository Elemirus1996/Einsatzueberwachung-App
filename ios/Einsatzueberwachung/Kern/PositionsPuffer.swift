import Foundation
import SQLite3

/// Zwischenspeicher für Positionen, damit im Funkloch nichts verloren geht.
@MainActor
protocol PositionsSpeicher: AnyObject {
    func hinzufuegen(_ position: Position)
    /// Älteste Einträge zuerst, höchstens `anzahl`.
    func aelteste(_ anzahl: Int) -> [(id: Int64, position: Position)]
    func loeschen(bisEinschliesslich id: Int64)
    func anzahl() -> Int
    func leeren()
}

/// SQLite-Datei im Application-Support-Ordner, durch die Dateiverschlüsselung des iPhones geschützt.
@MainActor
final class PositionsPuffer: PositionsSpeicher {
    private var db: OpaquePointer?

    init(datei: URL) {
        try? FileManager.default.createDirectory(at: datei.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard sqlite3_open(datei.path, &db) == SQLITE_OK else {
            db = nil
            return
        }
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: datei.path)
        ausfuehren("""
            CREATE TABLE IF NOT EXISTS positionen (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL, lng REAL NOT NULL, accuracy REAL,
                timestamp INTEGER NOT NULL, battery INTEGER)
            """)
    }

    static func standard() -> PositionsPuffer {
        let ordner = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return PositionsPuffer(datei: ordner.appending(path: "positionen.sqlite"))
    }

    isolated deinit {
        sqlite3_close(db)
    }

    func hinzufuegen(_ p: Position) {
        guard let stmt = vorbereiten("INSERT INTO positionen (lat, lng, accuracy, timestamp, battery) VALUES (?, ?, ?, ?, ?)") else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_double(stmt, 1, p.lat)
        sqlite3_bind_double(stmt, 2, p.lng)
        if let a = p.accuracy { sqlite3_bind_double(stmt, 3, a) } else { sqlite3_bind_null(stmt, 3) }
        sqlite3_bind_int64(stmt, 4, p.timestamp)
        if let b = p.battery { sqlite3_bind_int(stmt, 5, Int32(b)) } else { sqlite3_bind_null(stmt, 5) }
        sqlite3_step(stmt)
    }

    func aelteste(_ anzahl: Int) -> [(id: Int64, position: Position)] {
        guard let stmt = vorbereiten("SELECT id, lat, lng, accuracy, timestamp, battery FROM positionen ORDER BY id LIMIT ?") else { return [] }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int(stmt, 1, Int32(anzahl))
        var ergebnis: [(id: Int64, position: Position)] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            let position = Position(
                lat: sqlite3_column_double(stmt, 1),
                lng: sqlite3_column_double(stmt, 2),
                accuracy: sqlite3_column_type(stmt, 3) == SQLITE_NULL ? nil : sqlite3_column_double(stmt, 3),
                timestamp: sqlite3_column_int64(stmt, 4),
                battery: sqlite3_column_type(stmt, 5) == SQLITE_NULL ? nil : Int(sqlite3_column_int(stmt, 5)))
            ergebnis.append((sqlite3_column_int64(stmt, 0), position))
        }
        return ergebnis
    }

    func loeschen(bisEinschliesslich id: Int64) {
        guard let stmt = vorbereiten("DELETE FROM positionen WHERE id <= ?") else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, id)
        sqlite3_step(stmt)
    }

    func anzahl() -> Int {
        guard let stmt = vorbereiten("SELECT COUNT(*) FROM positionen") else { return 0 }
        defer { sqlite3_finalize(stmt) }
        return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
    }

    func leeren() {
        ausfuehren("DELETE FROM positionen")
    }

    private func vorbereiten(_ sql: String) -> OpaquePointer? {
        var stmt: OpaquePointer?
        guard db != nil, sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return nil }
        return stmt
    }

    private func ausfuehren(_ sql: String) {
        guard db != nil else { return }
        sqlite3_exec(db, sql, nil, nil, nil)
    }
}
