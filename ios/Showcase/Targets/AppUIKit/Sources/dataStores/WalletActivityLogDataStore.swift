import Foundation
import SQLite

class WalletActivityLogDataStore {

    static let DIR_ACTIVITY_LOG_DB = "ActivityLogDB"
    static let STORE_NAME = "wallet_activity_logs.sqlite3"

    private let walletActivityLogs = Table("wallet_activity_logs")

    private let id = SQLite.Expression<Int64>("id")
    private let credentialPackId = SQLite.Expression<String>(
        "credential_pack_id")
    private let credentialId = SQLite.Expression<String>("credential_id")
    private let credentialTitle = SQLite.Expression<String>("credential_title")
    private let issuer = SQLite.Expression<String>("issuer")
    private let action = SQLite.Expression<String>("action")
    private let dateTime = SQLite.Expression<Date>("date_time")
    private let additionalInformation = SQLite.Expression<String>(
        "additional_information")

    static let shared = WalletActivityLogDataStore()

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
                walletActivityLogs.create { table in
                    table.column(id, primaryKey: .autoincrement)
                    table.column(credentialPackId)
                    table.column(credentialId)
                    table.column(credentialTitle)
                    table.column(issuer)
                    table.column(action)
                    table.column(dateTime)
                    table.column(additionalInformation)
                })
            print("Table Created...")
        } catch {
            print(error)
        }
    }

    func insert(
        credentialPackId: String,
        credentialId: String,
        credentialTitle: String,
        issuer: String,
        action: String,
        dateTime: Date,
        additionalInformation: String
    ) -> Int64? {
        guard let database = db else { return nil }

        let insert = walletActivityLogs.insert(
            self.credentialPackId <- credentialPackId,
            self.credentialId <- credentialId,
            self.credentialTitle <- credentialTitle,
            self.issuer <- issuer,
            self.action <- action,
            self.dateTime <- dateTime,
            self.additionalInformation <- additionalInformation)
        do {
            let rowID = try database.run(insert)
            return rowID
        } catch {
            print(error)
            return nil
        }
    }

    func getAllWalletActivityLogs() -> [WalletActivityLog] {
        var walletActivityLogs: [WalletActivityLog] = []
        guard let database = db else { return [] }

        let dateTimeFormatterDisplay = {
            let dtFormatter = DateFormatter()
            dtFormatter.dateStyle = .medium
            dtFormatter.timeStyle = .short
            dtFormatter.locale = Locale(identifier: "en_US_POSIX")
            return dtFormatter
        }()

        do {
            for walletActivityLog in try database.prepare(
                self.walletActivityLogs.order(dateTime.desc)) {
                walletActivityLogs.append(
                    WalletActivityLog(
                        id: walletActivityLog[id],
                        credential_pack_id: walletActivityLog[credentialPackId],
                        credential_id: walletActivityLog[credentialId],
                        credential_title: walletActivityLog[
                            credentialTitle],
                        issuer: walletActivityLog[issuer],
                        action: walletActivityLog[action],
                        date_time: dateTimeFormatterDisplay.string(
                            from: walletActivityLog[dateTime]),
                        additional_information: walletActivityLog[
                            additionalInformation]
                    )
                )
            }
        } catch {
            print(error)
        }
        return walletActivityLogs
    }

    func delete(id: Int64) -> Bool {
        guard let database = db else {
            return false
        }
        do {
            let filter = walletActivityLogs.filter(self.id == id)
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
            for walletActivityLog in try database.prepare(
                self.walletActivityLogs)
            where !delete(id: walletActivityLog[id]) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }
}
