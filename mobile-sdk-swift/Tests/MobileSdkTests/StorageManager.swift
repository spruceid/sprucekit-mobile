import XCTest
@testable import SpruceIDMobileSdk

final class StorageManagerTest: XCTestCase {
    func testStorage() throws {
        let storeman = StorageManager()
        let key = "test_key"
        let value = Data("Some random string of text. 😎".utf8)

        XCTAssertNoThrow(try storeman.add(key: key, value: value))

        let payload = try storeman.get(key: key)

        XCTAssert(payload == value, "\(classForCoder):\(#function): Mismatch between stored & retrieved value.")

        XCTAssertNoThrow(try storeman.remove(key: key))
    }
}
