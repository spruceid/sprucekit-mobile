import Foundation
import SQLite

class WalletActivityLogDataStore {
    private let walletActivityLogs = Table(
        DatabaseManager.TABLE_WALLET_ACTIVITY_LOGS
    )

    private let id = SQLite.Expression<Int64>("id")
    private let credentialPackId = SQLite.Expression<String>(
        "credential_pack_id"
    )
    private let credentialId = SQLite.Expression<String>("credential_id")
    private let credentialTitle = SQLite.Expression<String>("credential_title")
    private let issuer = SQLite.Expression<String>("issuer")
    private let action = SQLite.Expression<String>("action")
    private let dateTime = SQLite.Expression<Date>("date_time")
    private let additionalInformation = SQLite.Expression<String>(
        "additional_information"
    )

    static let shared = WalletActivityLogDataStore()

    private init() {
        createTableIfNotExists()
        migrateFromOldDatabase()
    }

    private func getDatabase() -> Connection? {
        return DatabaseManager.shared.getDatabase()
    }

    private func createTableIfNotExists() {
        DatabaseManager.shared.createTableIfNotExists(
            DatabaseManager.TABLE_WALLET_ACTIVITY_LOGS
        ) { table in
            table.column(id, primaryKey: .autoincrement)
            table.column(credentialPackId)
            table.column(credentialId)
            table.column(credentialTitle)
            table.column(issuer)
            table.column(action)
            table.column(dateTime)
            table.column(additionalInformation)
        }
    }

    private func migrateFromOldDatabase() {
        let oldDbPath = DatabaseManager.shared.getOldDatabasePath(
            "ActivityLogDB/wallet_activity_logs.sqlite3"
        )

        DatabaseManager.shared.migrateFromOldDatabase(
            oldDbPath,
            tableName: DatabaseManager.TABLE_WALLET_ACTIVITY_LOGS
        ) { oldDb, newDb in
            let oldTable = Table("wallet_activity_logs")
            let oldId = SQLite.Expression<Int64>("id")
            let oldCredentialPackId = SQLite.Expression<String>(
                "credential_pack_id"
            )
            let oldCredentialId = SQLite.Expression<String>("credential_id")
            let oldCredentialTitle = SQLite.Expression<String>(
                "credential_title"
            )
            let oldIssuer = SQLite.Expression<String>("issuer")
            let oldAction = SQLite.Expression<String>("action")
            let oldDateTime = SQLite.Expression<Date>("date_time")
            let oldAdditionalInformation = SQLite.Expression<String>(
                "additional_information"
            )

            let newTable = Table(DatabaseManager.TABLE_WALLET_ACTIVITY_LOGS)
            let newId = SQLite.Expression<Int64>("id")
            let newCredentialPackId = SQLite.Expression<String>(
                "credential_pack_id"
            )
            let newCredentialId = SQLite.Expression<String>("credential_id")
            let newCredentialTitle = SQLite.Expression<String>(
                "credential_title"
            )
            let newIssuer = SQLite.Expression<String>("issuer")
            let newAction = SQLite.Expression<String>("action")
            let newDateTime = SQLite.Expression<Date>("date_time")
            let newAdditionalInformation = SQLite.Expression<String>(
                "additional_information"
            )

            for row in try oldDb.prepare(oldTable) {
                let insert = newTable.insert(
                    newId <- row[oldId],
                    newCredentialPackId <- row[oldCredentialPackId],
                    newCredentialId <- row[oldCredentialId],
                    newCredentialTitle <- row[oldCredentialTitle],
                    newIssuer <- row[oldIssuer],
                    newAction <- row[oldAction],
                    newDateTime <- row[oldDateTime],
                    newAdditionalInformation <- row[oldAdditionalInformation]
                )
                try newDb.run(insert)
            }

            let count = try oldDb.scalar(oldTable.count)
            print("Wallet Activity Logs: Migrated \(count) records")
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
        guard let database = getDatabase() else { return nil }

        let insert = walletActivityLogs.insert(
            self.credentialPackId <- credentialPackId,
            self.credentialId <- credentialId,
            self.credentialTitle <- credentialTitle,
            self.issuer <- issuer,
            self.action <- action,
            self.dateTime <- dateTime,
            self.additionalInformation <- additionalInformation
        )
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
        guard let database = getDatabase() else { return [] }

        let dateTimeFormatterDisplay = {
            let dtFormatter = DateFormatter()
            dtFormatter.dateStyle = .medium
            dtFormatter.timeStyle = .short
            dtFormatter.locale = Locale(identifier: "en_US_POSIX")
            return dtFormatter
        }()

        do {
            for walletActivityLog in try database.prepare(
                self.walletActivityLogs.order(dateTime.desc)
            ) {
                walletActivityLogs.append(
                    WalletActivityLog(
                        id: walletActivityLog[id],
                        credential_pack_id: walletActivityLog[credentialPackId],
                        credential_id: walletActivityLog[credentialId],
                        credential_title: walletActivityLog[
                            credentialTitle
                        ],
                        issuer: walletActivityLog[issuer],
                        action: walletActivityLog[action],
                        date_time: dateTimeFormatterDisplay.string(
                            from: walletActivityLog[dateTime]
                        ),
                        additional_information: walletActivityLog[
                            additionalInformation
                        ]
                    )
                )
            }
        } catch {
            print(error)
        }
        return walletActivityLogs
    }

    func delete(id: Int64) -> Bool {
        guard let database = getDatabase() else {
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
        guard let database = getDatabase() else { return false }
        do {
            for walletActivityLog in try database.prepare(
                self.walletActivityLogs
            )
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
