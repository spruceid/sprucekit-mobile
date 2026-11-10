import XCTest

@testable import MobileSdk

final class StorageManagerTest: XCTestCase {
  func testStorage() async throws {
    let storeman = StorageManager(appGroupId: nil)
    let key = "test_key"
    let value = Data("Some random string of text. 😎".utf8)

    do {
      try await storeman.add(key: key, value: value)
    } catch {
      XCTFail("Failed StorageManager.add")
    }

    let payload = try await storeman.get(key: key)

    XCTAssert(
      payload == value, "\(classForCoder):\(#function): Mismatch between stored & retrieved value.")

    do {
      try await storeman.remove(key: key)
    } catch {
      XCTFail("Failed StorageManager.remove")
    }
  }

  func testAddExcludesFromBackup() async throws {
    let storeman = StorageManager(appGroupId: nil)
    let key = "test_backup_key"
    let storedPath = await storeman.path(file: key)
    let file = try XCTUnwrap(storedPath)
    let datadir = file.deletingLastPathComponent()
    try? FileManager.default.removeItem(at: file)
    defer { try? FileManager.default.removeItem(at: file) }

    // The directory outlives a test run, so clear its flag first: the check below must see add() set it.
    try setExcludedFromBackup(datadir, false)

    try await storeman.add(key: key, value: Data("first".utf8))
    try assertExcluded(file: file, datadir: datadir, moment: "after the first write")

    // Clear both flags, then overwrite: add() must set them again on every write.
    try setExcludedFromBackup(file, false)
    try setExcludedFromBackup(datadir, false)
    let second = Data("second".utf8)
    try await storeman.add(key: key, value: second)
    try assertExcluded(file: file, datadir: datadir, moment: "after the overwrite")

    let payload = try await storeman.get(key: key)
    XCTAssertEqual(payload, second, "The overwrite did not store the new value.")
  }

  func testReadExcludesExistingStore() async throws {
    let storeman = StorageManager(appGroupId: nil)
    let key = "test_backup_legacy_key"
    let storedPath = await storeman.path(file: key)
    let file = try XCTUnwrap(storedPath)
    let datadir = file.deletingLastPathComponent()
    defer { try? FileManager.default.removeItem(at: file) }

    // A store left by an older SDK version: a file in place and no flags.
    let legacy = Data("legacy".utf8)
    try legacy.write(to: file)
    try setExcludedFromBackup(datadir, false)

    let payload = try await storeman.get(key: key)
    XCTAssertEqual(payload, legacy, "get() did not return the existing value.")
    XCTAssertEqual(try isExcludedFromBackup(datadir), true, "get() did not exclude the existing datastore.")

    try setExcludedFromBackup(datadir, false)
    let keys = try await storeman.list()
    XCTAssertTrue(keys.contains(key), "list() did not return the existing key.")
    XCTAssertEqual(try isExcludedFromBackup(datadir), true, "list() did not exclude the existing datastore.")

    try setExcludedFromBackup(datadir, false)
    try await storeman.remove(key: key)
    XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "remove() did not delete the file.")
    XCTAssertEqual(try isExcludedFromBackup(datadir), true, "remove() did not exclude the existing datastore.")
  }

  private func setExcludedFromBackup(_ url: URL, _ excluded: Bool) throws {
    var url = url
    var values = URLResourceValues()
    values.isExcludedFromBackup = excluded
    try url.setResourceValues(values)
  }

  /// Reads the flag through a fresh URL, so it comes from disk and not from a URL's cache.
  private func isExcludedFromBackup(_ url: URL) throws -> Bool? {
    try URL(fileURLWithPath: url.path).resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
  }

  private func assertExcluded(file: URL, datadir: URL, moment: String, line: UInt = #line) throws {
    XCTAssertEqual(
      try isExcludedFromBackup(file), true, "Stored file is not excluded from backup \(moment).", line: line)
    XCTAssertEqual(
      try isExcludedFromBackup(datadir), true, "Datastore directory is not excluded from backup \(moment).",
      line: line)
  }
}
