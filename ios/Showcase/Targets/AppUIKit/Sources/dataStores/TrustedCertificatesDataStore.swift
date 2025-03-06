import Foundation
import SQLite

struct TrustedCertificate: Hashable {
    let id: Int64
    let name: String
    let content: String
}

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
    )
]

class TrustedCertificatesDataStore {

    static let DIR_ACTIVITY_LOG_DB = "TrustedCertificatesDB"
    static let STORE_NAME = "trusted_certificates.sqlite3"

    private let trustedCertificates = Table("trusted_certificates")

    private let id = SQLite.Expression<Int64>("id")
    private let name = SQLite.Expression<String>("name")
    private let content = SQLite.Expression<String>("content")

    static let shared = TrustedCertificatesDataStore()

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

                checkAndInsertDefaultCertificates()
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
                trustedCertificates.create { table in
                    table.column(id, primaryKey: .autoincrement)
                    table.column(name)
                    table.column(content)
                })
            print("Table Created...")
        } catch {
            print(error)
        }
    }

    private func checkAndInsertDefaultCertificates() {
        guard let database = db else { return }

        do {
            let count = try database.scalar(trustedCertificates.count)
            if count == 0 {
                DEFAULT_TRUST_ANCHOR_CERTIFICATES.forEach { certificate in
                    _ = insert(name: certificate.0, content: certificate.1)
                }
            }
        } catch {
            print("Error checking certificates: \(error)")
        }
    }

    func insert(
        name: String,
        content: String
    ) -> Int64? {
        guard let database = db else { return nil }

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
        guard let database = db else { return [] }

        do {
            for certificate in try database.prepare(
                self.trustedCertificates) {
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
        guard let database = db else { return nil }

        do {
            for certificate in try database.prepare(
                self.trustedCertificates) {
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
        guard let database = db else {
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
        guard let database = db else {
            return false
        }
        do {
            for certificate in try database.prepare(
                self.trustedCertificates)
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
