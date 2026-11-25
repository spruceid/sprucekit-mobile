import Foundation
import mobile_sdk_rs

public class MockStorageManager: StorageManagerInterface {
    private var storage: [Key: Value] = [:]

    public func add(key: Key, value: Value) async throws {
        storage[key] = value
    }

    public func get(key: Key) async throws -> Value? {
        return storage[key]
    }

    public func list() async throws -> [Key] {
        return Array(storage.keys)
    }

    public func remove(key: Key) async throws {
        storage.removeValue(forKey: key)
    }
}

var counter = DispatchGroup()
counter.enter()
Task {
    do {
        let storage = MockStorageManager()
        let vdc = VdcCollection(engine: storage)
        let mdocPayload = try String(contentsOf: URL(fileURLWithPath: "../../../tests/res/mdoc.b64"))
        let mdocBytes = Data(base64Encoded: mdocPayload)
        let uuid = UUID()
        try await vdc.add(
            credential: Credential.init(
                id: uuid.uuidString,
                format: CredentialFormat.msoMdoc,
                type: CredentialType("org.iso.18013.5.1.mDL"),
                payload: mdocBytes!,
                keyAlias: "alias"
            ))
        let bleUuid = UUID()
        let mdlSession = try await initializeMdlPresentation(
            mdocId: uuid.uuidString,
            uuid: bleUuid.uuidString,
            engagement: .qr,
            storageManager: storage,
        )
        assert(
            try! mdlSession.terminateSession()
                == Data([0xA1, 0x66, 0x73, 0x74, 0x61, 0x74, 0x75, 0x73, 0x14]))
    } catch {
        assertionFailure("\(error)")
    }
    counter.leave()
}
counter.wait()

// A foreign JwsSigner that throws an error other than JwsSignatureError must
// reach Rust as JwsSignatureError::Other with the error's description, not panic.
struct UndeclaredSignerError: Error, CustomStringConvertible {
    let description: String
}

final class ThrowingJwsSigner: JwsSigner, @unchecked Sendable {
    func fetchInfo() async throws -> JwsSignerInfo {
        return JwsSignerInfo(keyId: nil, algorithm: .es256)
    }

    func signBytes(signingBytes: Data) async throws -> Data {
        throw UndeclaredSignerError(description: "User authentication required: test key")
    }
}

counter.enter()
Task {
    do {
        _ = try await createJwtProof(
            issuer: nil,
            audience: "https://issuer.example.com",
            expireInSecs: nil,
            nonce: nil,
            signer: ThrowingJwsSigner()
        )
        assertionFailure("createJwtProof should fail when the signer throws")
    } catch let error as Oid4vciError {
        let message = String(describing: error)
        assert(
            message.contains("User authentication required: test key"),
            "unexpected error: \(message)")
    } catch {
        // A Rust panic (e.g. uniffi failing to lift the callback error) lands here.
        assertionFailure("expected Oid4vciError, got \(error)")
    }
    counter.leave()
}
counter.wait()