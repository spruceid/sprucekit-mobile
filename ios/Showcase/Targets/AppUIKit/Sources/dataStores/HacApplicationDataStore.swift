import Foundation
import SQLite

struct HacApplication: Hashable {
    let id: UUID
    let issuanceId: String
}

class HacApplicationDataStore {
    private let hacApplications = Table(DatabaseManager.TABLE_HAC_APPLICATIONS)

    private let id = SQLite.Expression<String>("id")
    private let issuanceId = SQLite.Expression<String>("issuanceId")

    static let shared = HacApplicationDataStore()

    private init() {
        createTableIfNotExists()
        migrateFromOldDatabase()
    }

    private func getDatabase() -> Connection? {
        return DatabaseManager.shared.getDatabase()
    }

    private func createTableIfNotExists() {
        guard let database = getDatabase() else { return }

        DatabaseManager.shared.createTableIfNotExists(
            DatabaseManager.TABLE_HAC_APPLICATIONS
        ) { table in
            table.column(id, primaryKey: true)
            table.column(issuanceId)
        }
    }

    private func migrateFromOldDatabase() {
        let oldDbPath = DatabaseManager.shared.getOldDatabasePath(
            "HacApplicationDB/hac_applications.sqlite3"
        )

        DatabaseManager.shared.migrateFromOldDatabase(
            oldDbPath,
            tableName: DatabaseManager.TABLE_HAC_APPLICATIONS
        ) { oldDb, newDb in
            let oldTable = Table("hac_applications")
            let oldId = SQLite.Expression<String>("id")
            let oldIssuanceId = SQLite.Expression<String>("issuanceId")

            let newTable = Table(DatabaseManager.TABLE_HAC_APPLICATIONS)
            let newId = SQLite.Expression<String>("id")
            let newIssuanceId = SQLite.Expression<String>("issuanceId")

            for row in try oldDb.prepare(oldTable) {
                let insert = newTable.insert(
                    newId <- row[oldId],
                    newIssuanceId <- row[oldIssuanceId]
                )
                try newDb.run(insert)
            }

            let count = try oldDb.scalar(oldTable.count)
            print("HAC Applications: Migrated \(count) records")
        }
    }

    func insert(issuanceId: String) -> UUID? {
        guard let database = getDatabase() else { return nil }

        let newId = UUID()
        let insert = hacApplications.insert(
            self.id <- newId.uuidString,
            self.issuanceId <- issuanceId
        )
        do {
            try database.run(insert)
            return newId
        } catch {
            print(error)
            return nil
        }
    }

    func getAllHacApplications() -> [HacApplication] {
        var applications: [HacApplication] = []
        guard let database = getDatabase() else { return [] }

        do {
            for application in try database.prepare(
                self.hacApplications
            ) {
                applications.append(
                    HacApplication(
                        id: UUID(uuidString: application[id]) ?? UUID(),
                        issuanceId: application[issuanceId]
                    )
                )
            }
        } catch {
            print(error)
        }
        return applications
    }

    func getHacApplication(issuanceId: String) -> HacApplication? {
        guard let database = getDatabase() else { return nil }

        do {
            let filter = hacApplications.filter(self.issuanceId == issuanceId)
            if let application = try database.pluck(filter) {
                return HacApplication(
                    id: UUID(uuidString: application[self.id]) ?? UUID(),
                    issuanceId: application[self.issuanceId]
                )
            }

        } catch {
            print(error)
        }
        return nil
    }

    func delete(id: UUID) -> Bool {
        guard let database = getDatabase() else {
            return false
        }
        do {
            let filter = hacApplications.filter(self.id == id.uuidString)
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
            for application in try database.prepare(
                self.hacApplications
            )
            where !delete(id: UUID(uuidString: application[id]) ?? UUID()) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }
}
