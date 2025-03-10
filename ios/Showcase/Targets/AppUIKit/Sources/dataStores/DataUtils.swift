import Foundation
import SQLite

func generateCSV(heading: String, rows: String, filename: String) -> URL? {
    var fileURL: URL!

    // file rows
    let stringData = heading + rows

    do {
        let path = try FileManager.default.url(
            for: .documentDirectory,
            in: .allDomainsMask,
            appropriateFor: nil,
            create: false)

        fileURL = path.appendingPathComponent(filename)

        // append string data to file
        try stringData.write(to: fileURL, atomically: true, encoding: .utf8)
        return fileURL
    } catch {
        print("error generating csv file")
    }

    return nil
}

func columnExists(db: Connection, tableName: String, columnName: String) -> Bool {
    let query = "PRAGMA table_info(\(tableName))"
    do {
        for row in try db.prepare(query) {
            if let name = row[1] as? String, name == columnName {
                return true
            }
        }
    } catch {
        print("Error checking column existence: \(error)")
    }
    return false
}

func addColumnIfNotExists(
    db: Connection, tableName: String, columnName: String,
    columnDefinition: String
) {
    if !columnExists(db: db, tableName: tableName, columnName: columnName) {
        do {
            let addColumnQuery =
                "ALTER TABLE \(tableName) ADD COLUMN \(columnName) \(columnDefinition)"
            try db.run(addColumnQuery)
            print("Column \(columnName) added successfully!")
        } catch {
            print("Error adding column \(columnName): \(error)")
        }
    } else {
        print("Column \(columnName) already exists.")
    }
}
