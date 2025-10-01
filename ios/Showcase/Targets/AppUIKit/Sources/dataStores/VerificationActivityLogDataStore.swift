import Foundation
import SQLite

class VerificationActivityLogDataStore {
    private let verificationActivityLogs = Table(
        DatabaseManager.TABLE_VERIFICATION_ACTIVITY_LOGS
    )

    private let id = SQLite.Expression<Int64>("id")
    private let credentialTitle = SQLite.Expression<String>("credential_title")
    private let issuer = SQLite.Expression<String>("issuer")
    private let status = SQLite.Expression<String>("status")
    private let verificationDateTime = SQLite.Expression<Date>(
        "verification_date_time"
    )
    private let additionalInformation = SQLite.Expression<String>(
        "additional_information"
    )

    static let shared = VerificationActivityLogDataStore()

    private init() {
        createTableIfNotExists()
        migrateFromOldDatabase()
    }

    private func getDatabase() -> Connection? {
        return DatabaseManager.shared.getDatabase()
    }

    private func createTableIfNotExists() {
        DatabaseManager.shared.createTableIfNotExists(
            DatabaseManager.TABLE_VERIFICATION_ACTIVITY_LOGS
        ) { table in
            table.column(id, primaryKey: .autoincrement)
            table.column(credentialTitle)
            table.column(issuer)
            table.column(status)
            table.column(verificationDateTime)
            table.column(additionalInformation)
        }
    }

    private func migrateFromOldDatabase() {
        let oldDbPath = DatabaseManager.shared.getOldDatabasePath(
            "ActivityLogDB/verification_activity_logs_2.sqlite3"
        )

        DatabaseManager.shared.migrateFromOldDatabase(
            oldDbPath,
            tableName: DatabaseManager.TABLE_VERIFICATION_ACTIVITY_LOGS
        ) { oldDb, newDb in
            let oldTable = Table("verification_activity_logs")
            let oldId = SQLite.Expression<Int64>("id")
            let oldCredentialTitle = SQLite.Expression<String>(
                "credential_title"
            )
            let oldIssuer = SQLite.Expression<String>("issuer")
            let oldStatus = SQLite.Expression<String>("status")
            let oldVerificationDateTime = SQLite.Expression<Date>(
                "verification_date_time"
            )
            let oldAdditionalInformation = SQLite.Expression<String>(
                "additional_information"
            )

            let newTable = Table(
                DatabaseManager.TABLE_VERIFICATION_ACTIVITY_LOGS
            )
            let newId = SQLite.Expression<Int64>("id")
            let newCredentialTitle = SQLite.Expression<String>(
                "credential_title"
            )
            let newIssuer = SQLite.Expression<String>("issuer")
            let newStatus = SQLite.Expression<String>("status")
            let newVerificationDateTime = SQLite.Expression<Date>(
                "verification_date_time"
            )
            let newAdditionalInformation = SQLite.Expression<String>(
                "additional_information"
            )

            for row in try oldDb.prepare(oldTable) {
                let insert = newTable.insert(
                    newId <- row[oldId],
                    newCredentialTitle <- row[oldCredentialTitle],
                    newIssuer <- row[oldIssuer],
                    newStatus <- row[oldStatus],
                    newVerificationDateTime <- row[oldVerificationDateTime],
                    newAdditionalInformation <- row[oldAdditionalInformation]
                )
                try newDb.run(insert)
            }

            let count = try oldDb.scalar(oldTable.count)
            print("Verification Activity Logs: Migrated \(count) records")
        }
    }

    func insert(
        credentialTitle: String,
        issuer: String,
        status: String,
        verificationDateTime: Date,
        additionalInformation: String
    ) -> Int64? {
        guard let database = getDatabase() else { return nil }

        let insert = verificationActivityLogs.insert(
            self.credentialTitle <- credentialTitle,
            self.issuer <- issuer,
            self.status <- status,
            self.verificationDateTime <- verificationDateTime,
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

    func getAllVerificationActivityLogs() -> [VerificationActivityLog] {
        var verificationActivityLogs: [VerificationActivityLog] = []
        guard let database = getDatabase() else { return [] }

        let dateTimeFormatterDisplay = {
            let dtFormatter = DateFormatter()
            dtFormatter.dateStyle = .medium
            dtFormatter.timeStyle = .short
            dtFormatter.locale = Locale(identifier: "en_US_POSIX")
            return dtFormatter
        }()

        do {
            for verificationActivityLog in try database.prepare(
                self.verificationActivityLogs.order(verificationDateTime.desc)
            ) {
                verificationActivityLogs.append(
                    VerificationActivityLog(
                        id: verificationActivityLog[id],
                        credential_title: verificationActivityLog[
                            credentialTitle
                        ],
                        issuer: verificationActivityLog[issuer],
                        status: verificationActivityLog[status],
                        verification_date_time: dateTimeFormatterDisplay.string(
                            from: verificationActivityLog[verificationDateTime]
                        ),
                        additional_information: verificationActivityLog[
                            additionalInformation
                        ]
                    )
                )
            }
        } catch {
            print(error)
        }
        return verificationActivityLogs
    }

    func delete(id: Int64) -> Bool {
        guard let database = getDatabase() else {
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
        guard let database = getDatabase() else {
            return false
        }
        do {
            for verificationActivityLog in try database.prepare(
                self.verificationActivityLogs
            )
            where !delete(id: verificationActivityLog[id]) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }

}
