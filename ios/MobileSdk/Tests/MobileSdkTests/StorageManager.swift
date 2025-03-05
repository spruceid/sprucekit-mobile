import XCTest

@testable import MobileSdk

final class StorageManagerTest: XCTestCase {
  func testStorage() async throws {
    let storeman = StorageManager()
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
}
