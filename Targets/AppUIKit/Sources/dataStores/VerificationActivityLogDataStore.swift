import Foundation
import SQLite

class VerificationActivityLogDataStore {

    static let DIR_ACTIVITY_LOG_DB = "ActivityLogDB"
    static let STORE_NAME = "verification_activity_logs.sqlite3"

    private let verificationActivityLogs = Table("verification_activity_logs")

    private let id = Expression<Int64>("id")
    private let name = Expression<String>("name")
    private let credentialTitle = Expression<String>("credential_title")
    private let expirationDate = Expression<Date>("expiration_date")
    private let status = Expression<String>("status")
    private let date = Expression<Date>("date")

    static let shared = VerificationActivityLogDataStore()

    private var db: Connection?

    private init() {
        if let docDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let dirPath = docDir.appendingPathComponent(Self.DIR_ACTIVITY_LOG_DB)

            do {
                try FileManager.default.createDirectory(
                    atPath: dirPath.path,
                    withIntermediateDirectories: true,
                    attributes: nil
                )
                let dbPath = dirPath.appendingPathComponent(Self.STORE_NAME).path
                db = try Connection(dbPath)
                createTable()
                print("SQLiteDataStore init successfully at: \(dbPath) ")
            } catch {
                db = nil
                print("SQLiteDataStore init error: \(error)")
            }
        } else {
            db = nil
        }
    }

    private func createTable() {
        guard let database = db else {
            return
        }
        do {
            try database.run(verificationActivityLogs.create { table in
                table.column(id, primaryKey: .autoincrement)
                table.column(name)
                table.column(credentialTitle)
                table.column(expirationDate)
                table.column(status)
                table.column(date)
            })
            print("Table Created...")
        } catch {
            print(error)
        }
    }

    func insert(name: String, credentialTitle: String, expirationDate: Date, status: String, date: Date) -> Int64? {
        guard let database = db else { return nil }

        let insert = verificationActivityLogs.insert(self.name <- name,
                                  self.credentialTitle <- credentialTitle,
                                  self.expirationDate <- expirationDate,
                                  self.status <- status,
                                  self.date <- date)
        do {
            let rowID = try database.run(insert)
            return rowID
        } catch {
            print(error)
            return nil
        }
    }

    func getAllVerificationActivityLogs() -> [VerificationActivityLog] {
        var verificationActivityLogs: [VerificationActivityLog] = []
        guard let database = db else { return [] }

        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "MM/dd/yyyy"
        dateFormatter.locale = Locale(identifier: "en_US_POSIX")

        do {
            for verificationActivityLog in try database.prepare(self.verificationActivityLogs) {
                verificationActivityLogs.append(
                    VerificationActivityLog(
                        id: verificationActivityLog[id],
                        name: verificationActivityLog[name],
                        credential_title: verificationActivityLog[credentialTitle],
                        expiration_date: dateFormatter.string(from: verificationActivityLog[expirationDate]),
                        status: verificationActivityLog[status],
                        date: dateFormatter.string(from: verificationActivityLog[date])
                    )
                )
            }
        } catch {
            print(error)
        }
        return verificationActivityLogs
    }

    func delete(id: Int64) -> Bool {
        guard let database = db else {
            return false
        }
        do {
            let filter = verificationActivityLogs.filter(self.id == id)
            try database.run(filter.delete())
            return true
        } catch {
            print(error)
            return false
        }
    }

    func deleteAll() -> Bool {
        guard let database = db else {
            return false
        }
        do {
            for verificationActivityLog in try database.prepare(self.verificationActivityLogs) where !delete(id: verificationActivityLog[id]) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }
}
