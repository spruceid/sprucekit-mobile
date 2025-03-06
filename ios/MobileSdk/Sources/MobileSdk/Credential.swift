import Foundation
import SpruceIDMobileSdkRs

open class Credential: Identifiable {
    public var id: String

    public init(id: String) {
        self.id = id
    }

    open func get(keys: [String]) -> [String: GenericJSON] {
        if keys.contains("id") {
            return ["id": GenericJSON.string(id)]
        } else {
            return [:]
        }
    }
}

public extension Mdoc {
    /// Access all of the elements in the mdoc, ignoring namespaces and missing elements that cannot be encoded as JSON.
    func jsonEncodedDetails() -> [String: GenericJSON] {
        jsonEncodedDetailsInternal(containing: nil)
    }

    /// Access the specified elements in the mdoc, ignoring namespaces and missing elements that cannot be encoded as
    /// JSON.
    func jsonEncodedDetails(containing elementIdentifiers: [String]) -> [String: GenericJSON] {
        jsonEncodedDetailsInternal(containing: elementIdentifiers)
    }

    private func jsonEncodedDetailsInternal(containing elementIdentifiers: [String]?) -> [String: GenericJSON] {
        // Ignore the namespaces.
        var uniqueValues = Set<String>()
        return Dictionary(uniqueKeysWithValues: details().flatMap {
            $1.compactMap {
                let id = $0.identifier

                // If a filter is provided, filter out non-specified ids.
                if let ids = elementIdentifiers {
                    if !ids.contains(id) {
                        return nil
                    }
                }
                // Filter duplicate fields
                if uniqueValues.contains(id) {
                    return nil
                }
                if let data = $0.value?.data(using: .utf8) {
                    do {
                        let json = try JSONDecoder().decode(GenericJSON.self, from: data)
                        uniqueValues.insert(id)
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

public extension JwtVc {
    /// Access the W3C VCDM credential (not including the JWT envelope).
    func credentialClaims() -> [String: GenericJSON] {
        if let data = credentialAsJsonEncodedUtf8String().data(using: .utf8) {
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
    func credentialClaims(containing claimNames: [String]) -> [String: GenericJSON] {
        credentialClaims().filter { key, _ in
            claimNames.contains(key)
        }
    }
}

public extension Cwt {
    /// Access the CWT credential
    func credentialClaims() -> [String: GenericJSON] {
        var result: [String: GenericJSON] = [:]

        for (key, value) in self.claims() {
            result[key] = value.toGenericJSON()
        }

        return result
    }

    /// Access the specified claims from the W3C VCDM credential.
    func credentialClaims(containing claimNames: [String]) -> [String: GenericJSON] {
        credentialClaims().filter { key, _ in
            claimNames.contains(key)
        }
    }
}

public extension JsonVc {
    /// Access the W3C VCDM credential
    func credentialClaims() -> [String: GenericJSON] {
        if let data = credentialAsJsonEncodedUtf8String().data(using: .utf8) {
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
    func credentialClaims(containing claimNames: [String]) -> [String: GenericJSON] {
        credentialClaims().filter { key, _ in
            claimNames.contains(key)
        }
    }
}

public extension Vcdm2SdJwt {
    /// Access the SD-JWT decoded credential
    func credentialClaims() -> [String: GenericJSON] {
        do {
            if let data = try revealedClaimsAsJsonString().data(using: .utf8) {
                do {
                    let json = try JSONDecoder().decode(GenericJSON.self, from: data)
                    if let object = json.dictValue {
                        return object
                    } else {
                        print("unexpected format for SD-JWT")
                    }
                } catch let error as NSError {
                    print("failed to decode as JSON: \(error)")
                }
            }
        } catch let error as NSError {
            print("failed to decode SD-JWT data from UTF-8: \(error)")
        }

        return [:]
    }

    /// Access the specified claims from the SD-JWT credential.
    func credentialClaims(containing claimNames: [String]) -> [String: GenericJSON] {
        credentialClaims().filter { key, _ in
            claimNames.contains(key)
        }
    }
}
