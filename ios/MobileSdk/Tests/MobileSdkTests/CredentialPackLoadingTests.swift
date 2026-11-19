import SpruceIDMobileSdkRs
import XCTest

@testable import MobileSdk

/// In-memory `StorageManagerInterface` that can be told to fail on `get` for some keys,
/// to list keys it does not hold, or to fail `list()` altogether.
private final class FakeStorageManager: StorageManagerInterface, @unchecked Sendable {
    struct InjectedError: Error {}

    private let lock = NSLock()
    private var keys: [Key] = []
    private var values: [Key: Value] = [:]
    private var getErrors: [Key: Error] = [:]
    private var listError: Error?

    private func sync<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    func add(key: Key, value: Value) async throws {
        sync {
            if values[key] == nil {
                keys.append(key)
            }
            values[key] = value
        }
    }

    func get(key: Key) async throws -> Value? {
        try sync {
            if let error = getErrors[key] {
                throw error
            }
            return values[key]
        }
    }

    func list() async throws -> [Key] {
        try sync {
            if let listError {
                throw listError
            }
            return keys
        }
    }

    func remove(key: Key) async throws {
        sync {
            keys.removeAll { $0 == key }
            values[key] = nil
        }
    }

    /// Lists `key`, but makes `get` throw `error` for it.
    func addFailingGet(key: Key, error: Error = InjectedError()) {
        sync {
            keys.append(key)
            getErrors[key] = error
        }
    }

    /// Makes `get` throw for a key that is already stored, without changing what is listed.
    func failGet(key: Key) {
        sync { getErrors[key] = InjectedError() }
    }

    func contains(key: Key) -> Bool {
        sync { keys.contains(key) }
    }

    /// Lists `key`, but makes `get` return nil for it.
    func addListedOnly(key: Key) {
        sync { keys.append(key) }
    }

    func failList(with error: Error) {
        sync { listError = error }
    }
}

final class CredentialPackLoadingTests: XCTestCase {
    // A JWT VC (`rust/tests/examples/vvt.jwt`). Parsing does not verify the signature.
    // swiftlint:disable:next line_length
    private let jwtVc = "eyJhbGciOiJFUzI1NiIsImtpZCI6ImRpZDp3ZWI6cWEub3BlbmNyZWQub3JnI3pEbmFleVU5Z2lIS1FYQVhZcTFjNWMyWE5XOGtRUGExWjN1TGM5NEtLQ05Hem5UYTMifQ.eyJpc3MiOiJkaWQ6d2ViOnFhLm9wZW5jcmVkLm9yZyIsImp0aSI6InVybjp1dWlkOjg2N2NiY2RiLWE5MGQtNDQxNy1iNWRlLTI1OGMzOGViN2ZhMSIsIm5iZiI6MTcyMzMxMDkyMywiZXhwIjoxNzU0ODQ2OTIzLCJ2YyI6eyJAY29udGV4dCI6WyJodHRwczovL3d3dy53My5vcmcvMjAxOC9jcmVkZW50aWFscy92MSIsImh0dHBzOi8vdzNpZC5vcmcvdnZ0L3YxcmMyIl0sInR5cGUiOlsiVmVyaWZpYWJsZUNyZWRlbnRpYWwiLCJWZWhpY2xlVGl0bGVDcmVkZW50aWFsIl0sImlkIjoidXJuOnV1aWQ6ODY3Y2JjZGItYTkwZC00NDE3LWI1ZGUtMjU4YzM4ZWI3ZmExIiwiaXNzdWFuY2VEYXRlIjoiMjAyNC0wOC0xMFQxNzoyODo0M1oiLCJleHBpcmF0aW9uRGF0ZSI6IjIwMjUtMDgtMTBUMTc6Mjg6NDNaIiwiY3JlZGVudGlhbFN1YmplY3QiOnsidHlwZSI6IlZlaGljbGVUaXRsZSIsIm93bmVyIjpbeyJ0eXBlIjoiUGVyc29uIiwibmFtZSI6IlRFU1RNT0UgTklORVRIUkVFIiwiaGFzQ2VydGlmaWNhdGlvbiI6eyJ0eXBlIjoiQ2VydGlmaWNhdGlvbiIsImFib3V0IjoiRHJpdmVyJ3MgTGljZW5zZSBOdW1iZXIiLCJjZXJ0aWZpY2F0aW9uSWRlbnRpZmljYXRpb24iOiJJODg4MjY5MyJ9fV0sInZlaGljbGUiOnsidHlwZSI6IlZlaGljbGUiLCJ2ZWhpY2xlSWRlbnRpZmljYXRpb25OdW1iZXIiOiJNVVkzVkdGNjRTQlg0Njg1NiJ9LCJwcm92ZW5hbmNlUmVzb3VyY2UiOiJodHRwczovL2NhZG1pdW0tdWF0LmRtdi5jYS5nb3YvZ3FsIzI1MjY2MzgifSwiaXNzdWVyIjoiZGlkOndlYjpxYS5vcGVuY3JlZC5vcmcifX0.6wSNhBWUcq9o0BfHTKQV-emmcVhYD40-vcBGOyYxFh63rsvdhfTf-Z_nOZehohPnYl1djII92MlwX9fdYciYxQ"

    private func savePack(
        _ storage: FakeStorageManager,
        credentials: [Uuid] = [],
        scope: String? = nil
    ) async throws -> UUID {
        let contents = CredentialPackContents(id: UUID(), credentials: credentials)
        try await contents.save(storageManager: storage, scope: scope)
        return contents.id
    }

    private func key(scope: String?) -> String {
        CredentialPackContents.storageKey(id: UUID(), scope: scope)
    }

    // MARK: - CredentialPackContents.list

    func testListSkipsBadEntriesBeforeAndAfterGoodOnes() async throws {
        let storage = FakeStorageManager()
        storage.addFailingGet(key: key(scope: nil))
        let first = try await savePack(storage)
        try await storage.add(key: key(scope: nil), value: Data("not json".utf8))
        let second = try await savePack(storage)
        try await storage.add(key: key(scope: nil), value: Data(#"{"id":"not-a-uuid","credentials":[]}"#.utf8))
        storage.addListedOnly(key: key(scope: nil))

        let packs = try await CredentialPackContents.list(storageManager: storage)

        XCTAssertEqual(packs.map(\.id), [first, second])
    }

    func testListOnlyBadEntriesReturnsEmpty() async throws {
        let storage = FakeStorageManager()
        storage.addFailingGet(key: key(scope: "alice"))
        storage.addListedOnly(key: key(scope: "alice"))

        let packs = try await CredentialPackContents.list(storageManager: storage, scope: "alice")

        XCTAssertTrue(packs.isEmpty)
    }

    func testListFiltersByScope() async throws {
        let storage = FakeStorageManager()
        let unscoped = try await savePack(storage)
        storage.addFailingGet(key: key(scope: "alice"))
        let alice = try await savePack(storage, scope: "alice")
        try await storage.add(key: key(scope: "alice"), value: Data("not json".utf8))
        let bob = try await savePack(storage, scope: "bob")
        try await storage.add(key: "unrelated", value: Data("not json".utf8))

        let unscopedPacks = try await CredentialPackContents.list(storageManager: storage)
        let alicePacks = try await CredentialPackContents.list(storageManager: storage, scope: "alice")
        let bobPacks = try await CredentialPackContents.list(storageManager: storage, scope: "bob")

        XCTAssertEqual(unscopedPacks.map(\.id), [unscoped])
        XCTAssertEqual(alicePacks.map(\.id), [alice])
        XCTAssertEqual(bobPacks.map(\.id), [bob])
    }

    func testListFailureThrowsListing() async throws {
        let storage = FakeStorageManager()
        _ = try await savePack(storage)
        storage.failList(with: FakeStorageManager.InjectedError())

        do {
            _ = try await CredentialPackContents.list(storageManager: storage)
            XCTFail("expected list to throw")
        } catch CredentialPackError.listing(let reason) {
            XCTAssertTrue(reason is FakeStorageManager.InjectedError)
        }
    }

    func testListRethrowsCancellation() async throws {
        let storage = FakeStorageManager()
        storage.failList(with: CancellationError())

        do {
            _ = try await CredentialPackContents.list(storageManager: storage)
            XCTFail("expected list to throw")
        } catch is CancellationError {
            // Expected: cancellation is not wrapped in CredentialPackError.listing.
        }
    }

    // MARK: - CredentialPack.loadAll

    func testLoadAllSkipsMissingCredentialAndLoadsOtherPacks() async throws {
        let storage = FakeStorageManager()
        let broken = try await savePack(storage, credentials: [UUID().uuidString])
        let empty = try await savePack(storage)
        storage.addFailingGet(key: key(scope: nil))

        let packs = try await CredentialPack.loadAll(storageManager: storage)

        XCTAssertEqual(packs.map(\.id), [broken, empty])
        XCTAssertTrue(packs.allSatisfy { $0.list().isEmpty })
    }

    func testLoadAllKeepsGoodCredentialAndSkipsMissingAndUnparseableOnes() async throws {
        let storage = FakeStorageManager()
        let vdcCollection = VdcCollection(engine: storage)

        let good = ParsedCredential.newJwtVcJson(jwtVc: try JwtVc.newFromCompactJws(jws: jwtVc))
        try await vdcCollection.add(credential: try good.intoGenericForm())

        let unparseableId = UUID().uuidString
        try await vdcCollection.add(
            credential: Credential(
                id: unparseableId,
                format: .jwtVcJson,
                type: "VehicleTitleCredential",
                payload: Data("not a jws".utf8),
                keyAlias: nil
            )
        )

        let pack = try await savePack(
            storage,
            credentials: [UUID().uuidString, good.id(), unparseableId],
            scope: "alice"
        )
        let other = try await savePack(storage, scope: "alice")

        let packs = try await CredentialPack.loadAll(storageManager: storage, scope: "alice")

        XCTAssertEqual(packs.map(\.id), [pack, other])
        XCTAssertEqual(packs.first?.list().map { $0.id() }, [good.id()])

        // Loading one pack skips the same credentials.
        let single = try await CredentialPack.load(storageManager: storage, id: pack, scope: "alice")
        XCTAssertEqual(single?.list().map { $0.id() }, [good.id()])
    }

    func testLoadSinglePackStillThrowsOnBadContents() async throws {
        let storage = FakeStorageManager()
        let id = UUID()
        try await storage.add(
            key: CredentialPackContents.storageKey(id: id, scope: nil),
            value: Data("not json".utf8)
        )

        do {
            _ = try await CredentialPack.load(storageManager: storage, id: id)
            XCTFail("expected load to throw")
        } catch CredentialPackError.contentsNotJSON {
            // Expected.
        }

        let missing = try await CredentialPack.load(storageManager: storage, id: UUID())
        XCTAssertNil(missing)
    }

    // MARK: - Credentials that could not be loaded

    /// The VdcCollection's storage key for a credential (`rust/src/vdc_collection.rs`).
    private func credentialKey(_ id: Uuid) -> Key {
        "Credential.\(id.lowercased())"
    }

    private struct StoredPack {
        let pack: UUID
        let good: Uuid
        let throwing: Uuid
        let unparseable: Uuid
    }

    /// Stores a good credential, one whose `get` throws, and one that does not parse, then a
    /// pack listing an absent credential and those three.
    private func savePackWithBadCredentials(_ storage: FakeStorageManager) async throws -> StoredPack {
        let vdcCollection = VdcCollection(engine: storage)

        let good = ParsedCredential.newJwtVcJson(jwtVc: try JwtVc.newFromCompactJws(jws: jwtVc))
        try await vdcCollection.add(credential: try good.intoGenericForm())

        let throwing = UUID().uuidString
        try await vdcCollection.add(
            credential: Credential(
                id: throwing,
                format: .jwtVcJson,
                type: "VehicleTitleCredential",
                payload: Data(jwtVc.utf8),
                keyAlias: nil
            )
        )
        storage.failGet(key: credentialKey(throwing))

        let unparseable = UUID().uuidString
        try await vdcCollection.add(
            credential: Credential(
                id: unparseable,
                format: .jwtVcJson,
                type: "VehicleTitleCredential",
                payload: Data("not a jws".utf8),
                keyAlias: nil
            )
        )

        let pack = try await savePack(
            storage,
            credentials: [UUID().uuidString, good.id(), throwing, unparseable],
            scope: "alice"
        )
        return StoredPack(pack: pack, good: good.id(), throwing: throwing, unparseable: unparseable)
    }

    func testSaveKeepsCredentialsThatCouldNotBeLoaded() async throws {
        let storage = FakeStorageManager()
        let stored = try await savePackWithBadCredentials(storage)

        let packs = try await CredentialPack.loadAll(storageManager: storage, scope: "alice")
        XCTAssertEqual(packs.map(\.id), [stored.pack])
        XCTAssertEqual(packs.first?.list().map { $0.id() }, [stored.good])

        try await packs.first?.save(storageManager: storage, scope: "alice")

        let contents = try await CredentialPackContents.list(storageManager: storage, scope: "alice")
        XCTAssertEqual(contents.map(\.id), [stored.pack])
        // The absent credential is dropped; the others stay, loaded first.
        XCTAssertEqual(contents.first?.credentials, [stored.good, stored.throwing, stored.unparseable])
    }

    func testRemoveDeletesCredentialsThatCouldNotBeLoaded() async throws {
        let storage = FakeStorageManager()
        let stored = try await savePackWithBadCredentials(storage)

        let pack = try await CredentialPack.load(storageManager: storage, id: stored.pack, scope: "alice")
        try await pack?.remove(storageManager: storage, scope: "alice")

        for credential in [stored.good, stored.throwing, stored.unparseable] {
            XCTAssertFalse(storage.contains(key: credentialKey(credential)), "credential '\(credential)' not removed")
        }
        XCTAssertFalse(storage.contains(key: CredentialPackContents.storageKey(id: stored.pack, scope: "alice")))
    }

    func testSaveListsUnloadedCredentialsAfterLoadedOnesWithoutDuplicates() async throws {
        let storage = FakeStorageManager()
        let good = ParsedCredential.newJwtVcJson(jwtVc: try JwtVc.newFromCompactJws(jws: jwtVc))
        let unloaded = UUID().uuidString
        let pack = CredentialPack(
            id: UUID(),
            credentials: [good],
            unloadedCredentialIds: [unloaded, good.id(), unloaded]
        )

        try await pack.save(storageManager: storage)

        let contents = try await CredentialPackContents.list(storageManager: storage)
        XCTAssertEqual(contents.first?.credentials, [good.id(), unloaded])
        // Only the loaded credential is written to the VdcCollection.
        XCTAssertTrue(storage.contains(key: credentialKey(good.id())))
        XCTAssertFalse(storage.contains(key: credentialKey(unloaded)))
    }

    // MARK: - Cancellation

    func testCancellationFromPackGetPropagatesOutOfListAndLoadAll() async throws {
        let storage = FakeStorageManager()
        _ = try await savePack(storage)
        storage.addFailingGet(key: key(scope: nil), error: CancellationError())

        do {
            _ = try await CredentialPackContents.list(storageManager: storage)
            XCTFail("expected list to throw")
        } catch is CancellationError {
            // Expected.
        }

        do {
            _ = try await CredentialPack.loadAll(storageManager: storage)
            XCTFail("expected loadAll to throw")
        } catch is CancellationError {
            // Expected.
        }
    }

    func testCancelledTaskPropagatesCancellationFromFailingCredentialRead() async throws {
        let storage = FakeStorageManager()
        let credentialId = UUID().uuidString
        storage.addFailingGet(key: credentialKey(credentialId))
        _ = try await savePack(storage, credentials: [credentialId])

        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await CredentialPack.loadAll(storageManager: storage)
        }

        do {
            _ = try await task.value
            XCTFail("expected loadAll to throw")
        } catch is CancellationError {
            // Expected: the read error is not skipped once the task is cancelled.
        }
    }
}
