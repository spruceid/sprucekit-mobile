import Foundation
import SQLite

struct TrustedCertificate: Hashable {
    let id: Int64
    let name: String
    let content: String
}

let DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_PROD = (
    "spruceid-haci-prod-certificate.pem",
    """
    -----BEGIN CERTIFICATE-----
    MIICJDCCAcqgAwIBAgIJAOE5hRXm8PnwMAoGCCqGSM49BAMCMEcxETAPBgNVBAoM
    CFNwcnVjZUlEMQswCQYDVQQIDAJOWTELMAkGA1UEBhMCVVMxGDAWBgNVBAMMD1Nw
    cnVjZUlEIG1ETCBDQTAeFw0yNTA1MjAxMDQyMDNaFw0zMDA1MTkxMDQyMDNaMEcx
    ETAPBgNVBAoMCFNwcnVjZUlEMQswCQYDVQQIDAJOWTELMAkGA1UEBhMCVVMxGDAW
    BgNVBAMMD1NwcnVjZUlEIG1ETCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IA
    BCl9YgK2qfIu4zO1br3YKeys5N7gznqjtW27w8brS4ejqeVYejdsoonT3GMSiJgs
    CjUgISZGZGTLD5uj8Qq5xImjgZ4wgZswHQYDVR0OBBYEFFEvLAdYAIUGN5BJiBOz
    VFFUphVhMB8GA1UdEgQYMBaGFGh0dHBzOi8vc3BydWNlaWQuY29tMDUGA1UdHwQu
    MCwwKqAooCaGJGh0dHBzOi8vY3JsLmhhY2kuc3BydWNlaWQueHl6L2lhY2EtMDAO
    BgNVHQ8BAf8EBAMCAQYwEgYDVR0TAQH/BAgwBgEB/wIBADAKBggqhkjOPQQDAgNI
    ADBFAiA3KpzFogVdNUCV+NTyBu+pEBDOmRFa735AFJMAOutzbAIhAJaRGpHvii65
    3q8/uns9PMOOf6rqN2R2hB7nUK5DEcVW
    -----END CERTIFICATE-----
    """
)

let DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_STAGING = (
    "spruceid-haci-staging-certificate.pem",
    """
    -----BEGIN CERTIFICATE-----
    MIICPjCCAeWgAwIBAgIIWVEstjwOoOAwCgYIKoZIzj0EAwIwUTERMA8GA1UECgwI
    U3BydWNlSUQxCzAJBgNVBAgMAk5ZMQswCQYDVQQGEwJVUzEiMCAGA1UEAwwZU3By
    dWNlSUQgbURMIENBIChTdGFnaW5nKTAeFw0yNTA0MDQxMTAxNDZaFw0zMDA0MDMx
    MTAxNDZaMFExETAPBgNVBAoMCFNwcnVjZUlEMQswCQYDVQQIDAJOWTELMAkGA1UE
    BhMCVVMxIjAgBgNVBAMMGVNwcnVjZUlEIG1ETCBDQSAoU3RhZ2luZykwWTATBgcq
    hkjOPQIBBggqhkjOPQMBBwNCAASJt4BBmA/JSHiIrWI00MMIgi8cJb0n67hpZjS6
    NipkOe8UIQtHzH+gyN8EARfwFD14X/vmy0wsWlQx3UlFL9wDo4GmMIGjMB0GA1Ud
    DgQWBBSMXLgb59dX6Vway9tD6N+5kGDktjAfBgNVHRIEGDAWhhRodHRwczovL3Nw
    cnVjZWlkLmNvbTA9BgNVHR8ENjA0MDKgMKAuhixodHRwczovL2NybC5oYWNpLnN0
    YWdpbmcuc3BydWNlaWQueHl6L2lhY2EtMDAOBgNVHQ8BAf8EBAMCAQYwEgYDVR0T
    AQH/BAgwBgEB/wIBADAKBggqhkjOPQQDAgNHADBEAiAiOqzt3ToFMtKdfw/ymsLm
    YulhRGtKCN95+3sKsGRxBwIgYU/+vwMuO6hY6KZYXb5FUS51xV6PSGUopGiBuTtM
    t7U=
    -----END CERTIFICATE-----
    """
)

let DEFAULT_TRUST_ANCHOR_CERTIFICATES = [
    // rust/tests/res/mdl/iaca-certificate.pem
    (
        "spruceid-iaca-certificate.pem",
        """
        -----BEGIN CERTIFICATE-----
        MIIB0zCCAXqgAwIBAgIJANVHM3D1VFaxMAoGCCqGSM49BAMCMCoxCzAJBgNVBAYT
        AlVTMRswGQYDVQQDDBJTcHJ1Y2VJRCBUZXN0IElBQ0EwHhcNMjUwMTA2MTA0MDUy
        WhcNMzAwMTA1MTA0MDUyWjAqMQswCQYDVQQGEwJVUzEbMBkGA1UEAwwSU3BydWNl
        SUQgVGVzdCBJQUNBMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmAZFZftRxWrl
        Iuf1ZY4DW7QfAfTu36RumpvYZnKVFUNmyrNxGrtQlp2Tbit+9lUzjBjF9R8nvdid
        mAHOMg3zg6OBiDCBhTAdBgNVHQ4EFgQUJpZofWBt6ci5UVfOl8E9odYu8lcwDgYD
        VR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwGwYDVR0SBBQwEoEQdGVz
        dEBleGFtcGxlLmNvbTAjBgNVHR8EHDAaMBigFqAUhhJodHRwOi8vZXhhbXBsZS5j
        b20wCgYIKoZIzj0EAwIDRwAwRAIgJFSMgE64Oiq7wdnWA3vuEuKsG0xhqW32HdjM
        LNiJpAMCIG82C+Kx875VNhx4hwfqReTRuFvZOTmFDNgKN0O/1+lI
        -----END CERTIFICATE-----
        """
    ),
    // rust/tests/res/mdl/utrecht-certificate.pem
    (
        "spruceid-utrecht-certificate.pem",
        """
        -----BEGIN CERTIFICATE-----
        MIICWTCCAf+gAwIBAgIULZgAnZswdEysOLq+G0uNW0svhYIwCgYIKoZIzj0EAwIw
        VjELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAk5ZMREwDwYDVQQKDAhTcHJ1Y2VJRDEn
        MCUGA1UEAwweU3BydWNlSUQgVGVzdCBDZXJ0aWZpY2F0ZSBSb290MB4XDTI1MDIx
        MjEwMjU0MFoXDTI2MDIxMjEwMjU0MFowVjELMAkGA1UEBhMCVVMxCzAJBgNVBAgM
        Ak5ZMREwDwYDVQQKDAhTcHJ1Y2VJRDEnMCUGA1UEAwweU3BydWNlSUQgVGVzdCBD
        ZXJ0aWZpY2F0ZSBSb290MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwWfpUAMW
        HkOzSctR8szsMNLeOCMyjk9HAkAYZ0HiHsBMNyrOcTxScBhEiHj+trE5d5fVq36o
        cvrVkt2X0yy/N6OBqjCBpzAdBgNVHQ4EFgQU+TKkY3MApIowvNzakcIr6P4ZQDQw
        EgYDVR0TAQH/BAgwBgEB/wIBADA+BgNVHR8ENzA1MDOgMaAvhi1odHRwczovL2lu
        dGVyb3BldmVudC5zcHJ1Y2VpZC5jb20vaW50ZXJvcC5jcmwwDgYDVR0PAQH/BAQD
        AgEGMCIGA1UdEgQbMBmBF2lzb2ludGVyb3BAc3BydWNlaWQuY29tMAoGCCqGSM49
        BAMCA0gAMEUCIAJrzCSS/VIjf7uTq+Kt6+97VUNSvaAAwdP6fscIvp4RAiEA0dOP
        Ld7ivuH83lLHDuNpb4NShfdBG57jNEIPNUs9OEg=
        -----END CERTIFICATE-----
        """
    ),
    // IACA Spruce HACI Staging
    DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_STAGING,
    // IACA Spruce HACI Prod
    DEFAULT_TRUST_ANCHOR_IACA_SPRUCEID_HACI_PROD,
]

class TrustedCertificatesDataStore {
    private let trustedCertificates = Table(
        DatabaseManager.TABLE_TRUSTED_CERTIFICATES
    )

    private let id = SQLite.Expression<Int64>("id")
    private let name = SQLite.Expression<String>("name")
    private let content = SQLite.Expression<String>("content")

    static let shared = TrustedCertificatesDataStore()

    private init() {
        createTableIfNotExists()
        migrateFromOldDatabase()
        insertDefaultCertificates()
    }

    private func getDatabase() -> Connection? {
        return DatabaseManager.shared.getDatabase()
    }

    private func createTableIfNotExists() {
        DatabaseManager.shared.createTableIfNotExists(
            DatabaseManager.TABLE_TRUSTED_CERTIFICATES
        ) { table in
            table.column(id, primaryKey: .autoincrement)
            table.column(name)
            table.column(content)
        }
    }

    private func migrateFromOldDatabase() {
        let oldDbPath = DatabaseManager.shared.getOldDatabasePath(
            "TrustedCertificatesDB/trusted_certificates.sqlite3"
        )

        DatabaseManager.shared.migrateFromOldDatabase(
            oldDbPath,
            tableName: DatabaseManager.TABLE_TRUSTED_CERTIFICATES
        ) { oldDb, newDb in
            let oldTable = Table("trusted_certificates")
            let oldId = SQLite.Expression<Int64>("id")
            let oldName = SQLite.Expression<String>("name")
            let oldContent = SQLite.Expression<String>("content")

            let newTable = Table(DatabaseManager.TABLE_TRUSTED_CERTIFICATES)
            let newId = SQLite.Expression<Int64>("id")
            let newName = SQLite.Expression<String>("name")
            let newContent = SQLite.Expression<String>("content")

            for row in try oldDb.prepare(oldTable) {
                let insert = newTable.insert(
                    newId <- row[oldId],
                    newName <- row[oldName],
                    newContent <- row[oldContent]
                )
                try newDb.run(insert)
            }

            let count = try oldDb.scalar(oldTable.count)
            print("Trusted Certificates: Migrated \(count) records")
        }
    }

    private func insertDefaultCertificates() {
        guard let database = getDatabase() else { return }

        do {
            let count = try database.scalar(trustedCertificates.count)

            if count == 0 {
                // Insert default certificates
                for certificate in DEFAULT_TRUST_ANCHOR_CERTIFICATES {
                    let insert = trustedCertificates.insert(
                        name <- certificate.0,
                        content <- certificate.1
                    )
                    try database.run(insert)
                }

                print(
                    "Trusted Certificates: Default certificates inserted successfully"
                )
            }
        } catch {
            print(
                "Trusted Certificates: Error inserting default certificates: \(error)"
            )
        }
    }

    func insert(
        name: String,
        content: String
    ) -> Int64? {
        guard let database = getDatabase() else { return nil }

        let insert = trustedCertificates.insert(
            self.name <- name,
            self.content <- content
        )
        do {
            let rowID = try database.run(insert)
            return rowID
        } catch {
            print(error)
            return nil
        }
    }

    func getAllCertificates() -> [TrustedCertificate] {
        var certificates: [TrustedCertificate] = []
        guard let database = getDatabase() else { return [] }

        do {
            for certificate in try database.prepare(
                self.trustedCertificates
            ) {
                certificates.append(
                    TrustedCertificate(
                        id: certificate[id],
                        name: certificate[name],
                        content: certificate[content]
                    )
                )
            }
        } catch {
            print(error)
        }
        return certificates
    }

    func getCertificate(rowId: Int64) -> TrustedCertificate? {
        guard let database = getDatabase() else { return nil }

        do {
            for certificate in try database.prepare(
                self.trustedCertificates
            ) {
                let elemId = certificate[id]
                if elemId == rowId {
                    return TrustedCertificate(
                        id: certificate[id],
                        name: certificate[name],
                        content: certificate[content]
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
            let filter = trustedCertificates.filter(self.id == id)
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
            for certificate in try database.prepare(
                self.trustedCertificates
            )
            where !delete(id: certificate[id]) {
                return false
            }
        } catch {
            print(error)
            return false
        }
        return true
    }
}
