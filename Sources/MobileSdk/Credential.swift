import Foundation

open class Credential: Identifiable {
    public var id: String

    public init(id: String) {
        self.id = id
    }

    open func get(keys: [String]) -> [String: GenericJSON] {
        if keys.contains("id") {
            return ["id": GenericJSON.string(self.id)]
        } else {
            return [:]
        }
    }
}
