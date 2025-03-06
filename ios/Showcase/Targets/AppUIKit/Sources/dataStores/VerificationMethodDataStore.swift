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

    static let DIR_ACTIVITY_LOG_DB = "VerificationMethodDB"
    static let STORE_NAME = "verification_methods.sqlite3"

    private let verificationMethods = Table("verification_methods")

    private let id = SQLite.Expression<Int64>("id")
    private let type = SQLite.Expression<String>("type")
    private let name = SQLite.Expression<String>("name")
    private let description = SQLite.Expression<String>("description")
    private let verifierName = SQLite.Expression<String>("verifierName")
    private let url = SQLite.Expression<String>("url")

    static let shared = VerificationMethodDataStore()

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
                verificationMethods.create { table in
                    table.column(id, primaryKey: .autoincrement)
                    table.column(type)
                    table.column(name)
                    table.column(description)
                    table.column(verifierName)
                    table.column(url)
                })
            print("Table Created...")
        } catch {
            print(error)
        }
    }

    func insert(
        type: String,
        name: String,
        description: String,
        verifierName: String,
        url: String
    ) -> Int64? {
        guard let database = db else { return nil }

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
        guard let database = db else { return [] }

        do {
            for verificationMethod in try database.prepare(
                self.verificationMethods) {
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
        guard let database = db else { return nil }

        do {
            for verificationMethod in try database.prepare(
                self.verificationMethods) {
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
        guard let database = db else {
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
        guard let database = db else {
            return false
        }
        do {
            for verificationMethod in try database.prepare(
                self.verificationMethods)
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
