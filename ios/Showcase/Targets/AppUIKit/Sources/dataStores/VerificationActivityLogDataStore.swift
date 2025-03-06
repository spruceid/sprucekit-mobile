import Foundation
import SQLite

class VerificationActivityLogDataStore {

    static let DIR_ACTIVITY_LOG_DB = "ActivityLogDB"
    static let STORE_NAME = "verification_activity_logs_2.sqlite3"
    static let TABLE_NAME = "verification_activity_logs"

    private let verificationActivityLogs = Table(TABLE_NAME)

    private let id = SQLite.Expression<Int64>("id")
    private let credentialTitle = SQLite.Expression<String>("credential_title")
    private let issuer = SQLite.Expression<String>("issuer")
    private let status = SQLite.Expression<String>("status")
    private let verificationDateTime = SQLite.Expression<Date>(
        "verification_date_time")
    private let additionalInformation = SQLite.Expression<String>(
        "additional_information")

    static let shared = VerificationActivityLogDataStore()

    private var db: Connection?

    private init() {
        if let docDir = FileManager.default.urls(
            for: .documentDirectory, in: .userDomainMask
        ).first {
            let dirPath = docDir.appendingPathComponent(
                Self.DIR_ACTIVITY_LOG_DB)

            do {
                try FileManager.default.createDirectory(
                    atPath: dirPath.path,
                    withIntermediateDirectories: true,
                    attributes: nil
                )
                let dbPath = dirPath.appendingPathComponent(Self.STORE_NAME)
                    .path
                db = try Connection(dbPath)
                createTable()
                print("SQLiteDataStore init successfully at: \(dbPath) ")
                try migration1(db: db.unwrap())
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
            try database.run(
                verificationActivityLogs.create { table in
                    table.column(id, primaryKey: .autoincrement)
                    table.column(credentialTitle)
                    table.column(issuer)
                    table.column(status)
                    table.column(verificationDateTime)
                    table.column(additionalInformation)
                })
            print("Table Created...")
        } catch {
            print(error)
        }
    }

    func insert(
        credentialTitle: String, issuer: String, status: String,
        verificationDateTime: Date,
        additionalInformation: String
    ) -> Int64? {
        guard let database = db else { return nil }

        let insert = verificationActivityLogs.insert(
            self.credentialTitle <- credentialTitle,
            self.issuer <- issuer,
            self.status <- status,
            self.verificationDateTime <- verificationDateTime,
            self.additionalInformation <- additionalInformation)
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

        let dateTimeFormatterDisplay = {
            let dtFormatter = DateFormatter()
            dtFormatter.dateStyle = .medium
            dtFormatter.timeStyle = .short
            dtFormatter.locale = Locale(identifier: "en_US_POSIX")
            return dtFormatter
        }()

        do {
            for verificationActivityLog in try database.prepare(
                self.verificationActivityLogs.order(verificationDateTime.desc)) {
                verificationActivityLogs.append(
                    VerificationActivityLog(
                        id: verificationActivityLog[id],
                        credential_title: verificationActivityLog[
                            credentialTitle],
                        issuer: verificationActivityLog[issuer],
                        status: verificationActivityLog[status],
                        verification_date_time: dateTimeFormatterDisplay.string(
                            from: verificationActivityLog[verificationDateTime]),
                        additional_information: verificationActivityLog[
                            additionalInformation]
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
            for verificationActivityLog in try database.prepare(
                self.verificationActivityLogs)
            where !delete(id: verificationActivityLog[id]) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }

    private func migration1(db: Connection) {
        addColumnIfNotExists(
            db: db,
            tableName: VerificationActivityLogDataStore.TABLE_NAME,
            columnName: "status",
            columnDefinition: "TEXT DEFAULT 'UNDEFINED'")
    }
}
