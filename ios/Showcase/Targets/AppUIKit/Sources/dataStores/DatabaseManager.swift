import Foundation
import SQLite

class DatabaseManager {
    static let shared = DatabaseManager()

    // Database configuration
    static let DB_DIR = "AppDatabase"
    static let DB_NAME = "showcase_app.sqlite3"
    static let CURRENT_DB_VERSION = 1

    // Table names
    static let TABLE_HAC_APPLICATIONS = "hac_applications"
    static let TABLE_TRUSTED_CERTIFICATES = "trusted_certificates"
    static let TABLE_VERIFICATION_METHODS = "verification_methods"
    static let TABLE_VERIFICATION_ACTIVITY_LOGS = "verification_activity_logs"
    static let TABLE_WALLET_ACTIVITY_LOGS = "wallet_activity_logs"
    static let TABLE_DB_VERSION = "db_version"

    private var db: Connection?
    private var isInitialized = false

    private init() {}

    func getDatabase() -> Connection? {
        if !isInitialized {
            initializeDatabase()
        }
        return db
    }

    func initializeDatabase() {
        guard !isInitialized else { return }

        if let docDir = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first {
            let dirPath = docDir.appendingPathComponent(Self.DB_DIR)

            do {
                try FileManager.default.createDirectory(
                    atPath: dirPath.path,
                    withIntermediateDirectories: true,
                    attributes: nil
                )

                let dbPath = dirPath.appendingPathComponent(Self.DB_NAME).path
                db = try Connection(dbPath)

                createTables()

                print("Database initialized successfully at: \(dbPath)")
                isInitialized = true

            } catch {
                db = nil
                print("Database initialization error: \(error)")
            }
        } else {
            db = nil
        }
    }

    private func createTables() {
        guard let database = db else { return }

        do {
            // Create db_version table
            let dbVersion = Table(Self.TABLE_DB_VERSION)
            let version = SQLite.Expression<Int>("version")

            try database.run(
                dbVersion.create(ifNotExists: true) { table in
                    table.column(version)
                }
            )

            // Insert initial version if table is empty
            let count = try database.scalar(dbVersion.count)
            if count == 0 {
                try database.run(
                    dbVersion.insert(version <- Self.CURRENT_DB_VERSION)
                )
            }

            print("Database tables created successfully")

        } catch {
            print("Error creating tables: \(error)")
        }
    }

    // Method for DataStores to create their own tables
    func createTableIfNotExists(
        _ tableName: String,
        createBlock: (TableBuilder) -> Void
    ) {
        guard let database = db else { return }

        do {
            let table = Table(tableName)
            try database.run(
                table.create(ifNotExists: true, block: createBlock)
            )
        } catch {
            print("Error creating table \(tableName): \(error)")
        }
    }

    // Method for DataStores to migrate their own data
    func migrateFromOldDatabase(
        _ oldDbPath: String,
        tableName: String,
        migrationBlock: (Connection, Connection) throws -> Void
    ) {
        guard let database = db else { return }

        // Check if migration is needed (only if unified database is empty for this table)
        do {
            let table = Table(tableName)
            let count = try database.scalar(table.count)
            if count > 0 {
                print("\(tableName): Data already exists, skipping migration")
                // Data already exists. Skip migration
                return
            }
        } catch {
            print("\(tableName): Error checking table count: \(error)")
            return
        }

        // Try to connect to old database
        guard let oldDb = try? Connection(oldDbPath) else {
            // Old database not found. Skip migration
            return
        }

        do {
            try database.transaction {
                try migrationBlock(oldDb, database)
            }
            print("\(tableName): Migration completed successfully")
        } catch {
            print("\(tableName): Migration error: \(error)")
        }
    }

    // Helper method to get old database path
    func getOldDatabasePath(_ relativePath: String) -> String {
        if let docDir = FileManager.default.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first {
            return docDir.appendingPathComponent(relativePath).path
        }
        return ""
    }
}
