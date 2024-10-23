import CryptoKit
import Foundation
import SpruceIDMobileSdkRs

public class CredentialPack {
    private var credentials: [ParsedCredential]

    /// Initialize an empty CredentialPack.
    public init() {
        credentials = []
    }

    /// Initialize a CredentialPack from existing credentials.
    public init(credentials: [ParsedCredential]) {
        self.credentials = credentials
    }

    /// Add a JwtVc to the CredentialPack.
    public func addJwtVc(jwtVc: JwtVc) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newJwtVcJson(jwtVc: jwtVc))
        return credentials
    }

    /// Add a JsonVc to the CredentialPack.
    public func addJsonVc(jsonVc: JsonVc) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newLdpVc(jsonVc: jsonVc))
        return credentials
    }

    /// Add an SD-JWT to the CredentialPack.
    public func addSdJwt(sdJwt: Vcdm2SdJwt) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newSdJwt(sdJwtVc: sdJwt))
        return credentials
    }

    /// Add an Mdoc to the CredentialPack.
    public func addMDoc(mdoc: Mdoc) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newMsoMdoc(mdoc: mdoc))
        return credentials
    }

    /// Find credential claims from all credentials in this CredentialPack.
    public func findCredentialClaims(claimNames: [String]) -> [Uuid: [String: GenericJSON]] {
        Dictionary(
            uniqueKeysWithValues: list()
                .map { credential in
                    var claims: [String: GenericJSON]
                    if let mdoc = credential.asMsoMdoc() {
                        if claimNames.isEmpty {
                            claims = mdoc.jsonEncodedDetails()
                        } else {
                            claims = mdoc.jsonEncodedDetails(containing: claimNames)
                        }
                    } else if let jwtVc = credential.asJwtVc() {
                        if claimNames.isEmpty {
                            claims = jwtVc.credentialClaims()
                        } else {
                            claims = jwtVc.credentialClaims(containing: claimNames)
                        }
                    } else if let jsonVc = credential.asJsonVc() {
                        if claimNames.isEmpty {
                            claims = jsonVc.credentialClaims()
                        } else {
                            claims = jsonVc.credentialClaims(containing: claimNames)
                        }
                    } else if let sdJwt = credential.asSdJwt() {
                        if claimNames.isEmpty {
                            claims = sdJwt.credentialClaims()
                        } else {
                            claims = sdJwt.credentialClaims(containing: claimNames)
                        }
                    } else {
                        var type: String
                        do {
                            type = try credential.intoGenericForm().type
                        } catch {
                            type = "unknown"
                        }
                        print("unsupported credential type: \(type)")
                        claims = [:]
                    }
                    return (credential.id(), claims)
                })
    }

    /// Get credentials by id.
    public func get(credentialsIds: [Uuid]) -> [ParsedCredential] {
        return credentials.filter {
            credentialsIds.contains($0.id())
        }
    }

    /// Get a credential by id.
    public func get(credentialId: Uuid) -> ParsedCredential? {
        return credentials.first(where: { $0.id() == credentialId })
    }

    /// List all of the credentials in the CredentialPack.
    public func list() -> [ParsedCredential] {
        return credentials
    }
}
