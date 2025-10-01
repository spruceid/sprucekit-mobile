import Foundation
import SQLite

struct VerificationMethod: Hashable {
    let id: Int64
    let type: String
    let name: String
    let description: String
    let verifierName: String
    let url: String
}

class VerificationMethodDataStore {
    private let verificationMethods = Table(
        DatabaseManager.TABLE_VERIFICATION_METHODS
    )

    private let id = SQLite.Expression<Int64>("id")
    private let type = SQLite.Expression<String>("type")
    private let name = SQLite.Expression<String>("name")
    private let description = SQLite.Expression<String>("description")
    private let verifierName = SQLite.Expression<String>("verifierName")
    private let url = SQLite.Expression<String>("url")

    static let shared = VerificationMethodDataStore()

    private init() {
        createTableIfNotExists()
        migrateFromOldDatabase()
    }

    private func getDatabase() -> Connection? {
        return DatabaseManager.shared.getDatabase()
    }

    private func createTableIfNotExists() {
        DatabaseManager.shared.createTableIfNotExists(
            DatabaseManager.TABLE_VERIFICATION_METHODS
        ) { table in
            table.column(id, primaryKey: .autoincrement)
            table.column(type)
            table.column(name)
            table.column(description)
            table.column(verifierName)
            table.column(url)
        }
    }

    private func migrateFromOldDatabase() {
        let oldDbPath = DatabaseManager.shared.getOldDatabasePath(
            "VerificationMethodDB/verification_methods.sqlite3"
        )

        DatabaseManager.shared.migrateFromOldDatabase(
            oldDbPath,
            tableName: DatabaseManager.TABLE_VERIFICATION_METHODS
        ) { oldDb, newDb in
            let oldTable = Table("verification_methods")
            let oldId = SQLite.Expression<Int64>("id")
            let oldType = SQLite.Expression<String>("type")
            let oldName = SQLite.Expression<String>("name")
            let oldDescription = SQLite.Expression<String>("description")
            let oldVerifierName = SQLite.Expression<String>("verifierName")
            let oldUrl = SQLite.Expression<String>("url")

            let newTable = Table(DatabaseManager.TABLE_VERIFICATION_METHODS)
            let newId = SQLite.Expression<Int64>("id")
            let newType = SQLite.Expression<String>("type")
            let newName = SQLite.Expression<String>("name")
            let newDescription = SQLite.Expression<String>("description")
            let newVerifierName = SQLite.Expression<String>("verifierName")
            let newUrl = SQLite.Expression<String>("url")

            for row in try oldDb.prepare(oldTable) {
                let insert = newTable.insert(
                    newId <- row[oldId],
                    newType <- row[oldType],
                    newName <- row[oldName],
                    newDescription <- row[oldDescription],
                    newVerifierName <- row[oldVerifierName],
                    newUrl <- row[oldUrl]
                )
                try newDb.run(insert)
            }

            let count = try oldDb.scalar(oldTable.count)
            print("Verification Methods: Migrated \(count) records")
        }
    }

    func insert(
        type: String,
        name: String,
        description: String,
        verifierName: String,
        url: String
    ) -> Int64? {
        guard let database = getDatabase() else { return nil }

        let insert = verificationMethods.insert(
            self.type <- type,
            self.name <- name,
            self.description <- description,
            self.verifierName <- verifierName,
            self.url <- url
        )
        do {
            let rowID = try database.run(insert)
            return rowID
        } catch {
            print(error)
            return nil
        }
    }

    func getAllVerificationMethods() -> [VerificationMethod] {
        var verificationMethods: [VerificationMethod] = []
        guard let database = getDatabase() else { return [] }

        do {
            for verificationMethod in try database.prepare(
                self.verificationMethods
            ) {
                verificationMethods.append(
                    VerificationMethod(
                        id: verificationMethod[id],
                        type: verificationMethod[type],
                        name: verificationMethod[name],
                        description: verificationMethod[description],
                        verifierName: verificationMethod[verifierName],
                        url: verificationMethod[url]
                    )
                )
            }
        } catch {
            print(error)
        }
        return verificationMethods
    }

    func getVerificationMethod(rowId: Int64) -> VerificationMethod? {
        guard let database = getDatabase() else { return nil }

        do {
            for verificationMethod in try database.prepare(
                self.verificationMethods
            ) {
                let elemId = verificationMethod[id]
                if elemId == rowId {
                    return VerificationMethod(
                        id: verificationMethod[id],
                        type: verificationMethod[type],
                        name: verificationMethod[name],
                        description: verificationMethod[description],
                        verifierName: verificationMethod[verifierName],
                        url: verificationMethod[url]
                    )
                }
            }
        } catch {
            print(error)
        }
        return nil
    }

    func delete(id: Int64) -> Bool {
        guard let database = getDatabase() else {
            return false
        }
        do {
            let filter = verificationMethods.filter(self.id == id)
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
            for verificationMethod in try database.prepare(
                self.verificationMethods
            )
            where !delete(id: verificationMethod[id]) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }
}
