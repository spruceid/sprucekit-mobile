import Foundation
import CryptoKit

public class CredentialPack {

    private var credentials: [Credential]

    public init() {
        self.credentials = []
    }

    public init(credentials: [Credential]) {
        self.credentials = credentials
    }

    public func addW3CVC(credentialString: String) throws -> [Credential]? {
        do {
            let credential = try W3CVC(credentialString: credentialString)
            self.credentials.append(credential)
            return self.credentials
        } catch {
            throw error
        }
    }

    public func addMDoc(mdocBase64: String, keyAlias: String = UUID().uuidString) throws -> [Credential]? {
        let mdocData = Data(base64Encoded: mdocBase64)!
        let credential = MDoc(fromMDoc: mdocData, namespaces: [:], keyAlias: keyAlias)!
        self.credentials.append(credential)
        return self.credentials
    }

    public func get(keys: [String]) -> [String: [String: GenericJSON]] {
        var values: [String: [String: GenericJSON]] = [:]
        for cred in self.credentials {
            values[cred.id] = cred.get(keys: keys)
        }

        return values
    }

    public func get(credentialsIds: [String]) -> [Credential] {
        return self.credentials.filter { credentialsIds.contains($0.id) }
    }

    public func get(credentialId: String) -> Credential? {
        if let credential = self.credentials.first(where: { $0.id == credentialId }) {
           return credential
        } else {
           return nil
        }
    }
}
