import Foundation
import SpruceIDMobileSdkRs

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

extension Mdoc {
    /// Access all of the elements in the mdoc, ignoring namespaces and missing elements that cannot be encoded as JSON.
    public func jsonEncodedDetails() -> [String: GenericJSON] {
        self.jsonEncodedDetailsInternal(containing: nil)
    }

    /// Access the specified elements in the mdoc, ignoring namespaces and missing elements that cannot be encoded as
    /// JSON.
    public func jsonEncodedDetails(containing elementIdentifiers: [String]) -> [String: GenericJSON] {
        self.jsonEncodedDetailsInternal(containing: elementIdentifiers)
    }

    private func jsonEncodedDetailsInternal(containing elementIdentifiers: [String]?) -> [String: GenericJSON] {
        // Ignore the namespaces.
        Dictionary(uniqueKeysWithValues: self.details().flatMap {
            $1.compactMap {
                let id = $0.identifier

                // If a filter is provided, filter out non-specified ids.
                if let ids = elementIdentifiers {
                    if !ids.contains(id) {
                        return nil
                    }
                }
                if let data = $0.value?.data(using: .utf8) {
                    do {
                        let json = try JSONDecoder().decode(GenericJSON.self, from: data)
                        return (id, json)
                    } catch let error as NSError {
                        print("failed to decode '\(id)' as JSON: \(error)")
                    }
                }
                return nil
            }
        })
    }
}

extension JwtVc {
    /// Access the W3C VCDM credential (not including the JWT envelope).
    public func credentialClaims() -> [String: GenericJSON] {
        if let data = self.credentialAsJsonEncodedUtf8String().data(using: .utf8) {
            do {
                let json = try JSONDecoder().decode(GenericJSON.self, from: data)
                if let object = json.dictValue {
                    return object
                } else {
                    print("unexpected format for VCDM")
                }
            } catch let error as NSError {
                print("failed to decode as JSON: \(error)")
            }
        }
        print("failed to decode VCDM data from UTF-8")
        return [:]
    }

    /// Access the specified claims from the W3C VCDM credential (not including the JWT envelope).
    public func credentialClaims(containing claimNames: [String]) -> [String: GenericJSON] {
        self.credentialClaims().filter { (key, _) in
            claimNames.contains(key)
        }
    }
}

extension JsonVc {
    /// Access the W3C VCDM credential
    public func credentialClaims() -> [String: GenericJSON] {
        if let data = self.credentialAsJsonEncodedUtf8String().data(using: .utf8) {
            do {
                let json = try JSONDecoder().decode(GenericJSON.self, from: data)
                if let object = json.dictValue {
                    return object
                } else {
                    print("unexpected format for VCDM")
                }
            } catch let error as NSError {
                print("failed to decode as JSON: \(error)")
            }
        }
        print("failed to decode VCDM data from UTF-8")
        return [:]
    }

    /// Access the specified claims from the W3C VCDM credential.
    public func credentialClaims(containing claimNames: [String]) -> [String: GenericJSON] {
        self.credentialClaims().filter { (key, _) in
            claimNames.contains(key)
        }
    }
}
