import Foundation
import SQLite

struct Credential: Hashable {
    let id: Int64
    let rawCredential: String
}

class CredentialDataStore {
    
    static let DIR_ACTIVITY_LOG_DB = "CredentialDB"
    static let STORE_NAME = "credentials.sqlite3"
    
    private let credentials = Table("credentials")
    
    private let id = SQLite.Expression<Int64>("id")
    private let rawCredential = SQLite.Expression<String>("raw_credential")
    
    static let shared = CredentialDataStore()
    
    private var db: Connection?
    
    private init() {
        if let docDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        {
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
            try database.run(
                credentials.create { table in
                    table.column(id, primaryKey: .autoincrement)
                    table.column(rawCredential)
                })
            print("Table Created...")
        } catch {
            print(error)
        }
    }
    
    func insert(rawCredential: String) -> Int64? {
        guard let database = db else { return nil }
        
        let insert = credentials.insert(self.rawCredential <- rawCredential)
        do {
            let rowID = try database.run(insert)
            return rowID
        } catch {
            print(error)
            return nil
        }
    }
    
    func getAllCredentials() -> [Credential] {
        var credentials: [Credential] = []
        guard let database = db else { return [] }
        
        do {
            for credential in try database.prepare(self.credentials) {
                credentials.append(
                    Credential(
                        id: credential[id],
                        rawCredential: credential[rawCredential]
                    )
                )
            }
        } catch {
            print(error)
        }
        return credentials
    }
    
    func getAllRawCredentials() -> [String] {
        var credentials: [String] = []
        guard let database = db else { return [] }
        
        do {
            for credential in try database.prepare(self.credentials) {
                credentials.append(credential[rawCredential])
            }
        } catch {
            print(error)
        }
        return credentials
    }
    
    func delete(id: Int64) -> Bool {
        guard let database = db else {
            return false
        }
        do {
            let filter = credentials.filter(self.id == id)
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
            for credential in try database.prepare(self.credentials)
            where !delete(id: credential[id]) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }
}
