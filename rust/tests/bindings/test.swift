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
        let mdlSession = try await initializeMdlPresentation(
            mdocId: uuid.uuidString, uuid: UUID().uuidString, storageManager: storage
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