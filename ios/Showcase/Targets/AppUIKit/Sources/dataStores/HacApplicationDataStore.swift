import Foundation
import SQLite

struct HacApplication: Hashable {
    let id: UUID
    let issuanceId: String
}

class HacApplicationDataStore {

    static let DIR_ACTIVITY_LOG_DB = "HacApplicationDB"
    static let STORE_NAME = "hac_applications.sqlite3"

    private let hacApplications = Table("hac_applications")

    private let id = SQLite.Expression<String>("id")
    private let issuanceId = SQLite.Expression<String>("issuanceId")

    static let shared = HacApplicationDataStore()

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
                hacApplications.create { table in
                    table.column(id, primaryKey: true)
                    table.column(issuanceId)
                })
            print("Table Created...")
        } catch {
            print(error)
        }
    }

    func insert(issuanceId: String) -> UUID? {
        guard let database = db else { return nil }

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
        guard let database = db else { return [] }

        do {
            for application in try database.prepare(
                self.hacApplications) {
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

    func getHacApplication(id: UUID) -> HacApplication? {
        guard let database = db else { return nil }

        do {
            for application in try database.prepare(
                self.hacApplications) {
                let elemId = UUID(uuidString: application[self.id]) ?? UUID()
                if elemId == id {
                    return HacApplication(
                        id: elemId,
                        issuanceId: application[issuanceId]
                    )
                }
            }
        } catch {
            print(error)
        }
        return nil
    }

    func delete(id: UUID) -> Bool {
        guard let database = db else {
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
        guard let database = db else {
            return false
        }
        do {
            for application in try database.prepare(
                self.hacApplications)
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
