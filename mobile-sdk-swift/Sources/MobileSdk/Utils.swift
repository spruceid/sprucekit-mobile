import Foundation
import SpruceIDMobileSdkRs

extension Sequence {
    func asyncMap<T>(_ transform: @escaping (Element) async throws -> T) async rethrows -> [T] {
        var results = [T]()
        for element in self {
            let result = try await transform(element)
            results.append(result)
        }
        return results
    }
}

extension Sequence {
    func asyncForEach(
        _ operation: (Element) async throws -> Void
    ) async rethrows {
        for element in self {
            try await operation(element)
        }
    }
}

extension CborValue {
    func toGenericJSON() -> GenericJSON {
        switch self {
        case .null:
            return .null
        case .bool(let bool):
            return .bool(bool)
        case .integer(let integer):
            return .number(Double(integer.toText())!)
        case .float(let double):
            return .number(double)
        case .bytes(let data):
            return .string(data.base64EncodedString())
        case .text(let string):
            return .string(string)
        case .array(let array):
            return .array(array.map { $0.toGenericJSON() })
        case .itemMap(let map):
            let jsonObject = map.reduce(into: [String: GenericJSON]()) { result, element in
                result["\(element.key)"] = element.value.toGenericJSON()
            }
            return .object(jsonObject)
        case .tag(let tag):
            return tag.value().toGenericJSON()
        }
    }
}
