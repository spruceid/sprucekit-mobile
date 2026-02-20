import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

// Get credential and throws error if can't parse
func credentialDisplayerSelector(
    rawCredential: String,
    goTo: (() -> Void)? = nil,
    onDelete: (() -> Void)? = nil
) async throws
    -> any ICredentialView
{
    return GenericCredentialItem(
        credentialPack: try await addCredential(
            credentialPack: CredentialPack(),
            rawCredential: rawCredential
        ),
        goTo: goTo,
        onDelete: onDelete
    )
}

func credentialDisplayerSelector(
    credentialPack: CredentialPack,
    goTo: (() -> Void)? = nil,
    onDelete: (() -> Void)? = nil
) -> any ICredentialView {
    return GenericCredentialItem(
        credentialPack: credentialPack,
        goTo: goTo,
        onDelete: onDelete
    )
}

func addCredential(credentialPack: CredentialPack, rawCredential: String) async throws
    -> CredentialPack
{
    if (try? credentialPack.addJwtVc(
        jwtVc: JwtVc.newFromCompactJws(jws: rawCredential))) != nil
    {
    } else if (try? credentialPack.addJsonVc(
        jsonVc: JsonVc.newFromJson(utf8JsonString: rawCredential))) != nil
    {
    } else if (try? credentialPack.addSdJwt(
        sdJwt: Vcdm2SdJwt.newFromCompactSdJwt(input: rawCredential))) != nil
    {
    } else if (try? credentialPack.addDcSdJwt(
        dcSdJwt: IetfSdJwtVc.newFromCompactSdJwt(input: rawCredential))) != nil
    {
    } else if (try? credentialPack.addCwt(
        cwt: Cwt.newFromBase10(payload: rawCredential))) != nil
    {
    } else if (try? await credentialPack.addMDoc(
        mdoc: Mdoc.fromStringifiedDocument(
            stringifiedDocument: rawCredential, keyAlias: UUID().uuidString)))
        != nil
    {
    } else if (try? await credentialPack.addMDoc(
        mdoc: Mdoc.newFromBase64urlEncodedIssuerSigned(
            base64urlEncodedIssuerSigned: rawCredential,
            keyAlias: UUID().uuidString)))
        != nil
    {
    } else {
        throw CredentialError.parsingError(
            "Couldn't parse credential: \(rawCredential)")
    }
    return credentialPack
}

func credentialHasType(credentialPack: CredentialPack, credentialType: String)
    -> Bool
{
    let credentialTypes = credentialPack.findCredentialClaims(claimNames: [
        "type"
    ])
    let credentialWithType = credentialTypes.first(where: { credential in
        credential.value["type"]?.arrayValue?.contains(where: { type in
            type.toString().lowercased() == credentialType.lowercased()
        }) ?? false
    })
    return credentialWithType != nil ? true : false
}

func credentialPackHasMdoc(credentialPack: CredentialPack) -> Bool {
    for credential in credentialPack.list() {
        if credential.asMsoMdoc() != nil {
            return true
        }
    }
    return false
}

func genericObjectFlattener(
    object: [String: GenericJSON], filter: [String] = []
) -> [String:
    String]
{
    var res: [String: String] = [:]
    object
        .filter { !filter.contains($0.key) }
        .forEach { (key, value) in
            if let dictValue = value.dictValue {
                res = genericObjectFlattener(object: dictValue, filter: filter)
                    .reduce(
                        into: [String: String](),
                        { result, x in
                            result["\(key).\(x.key)"] = x.value
                        })
            } else if let arrayValue = value.arrayValue {
                for (idx, item) in arrayValue.enumerated() {
                    genericObjectFlattener(
                        object: ["\(idx)": item], filter: filter
                    )
                    .forEach {
                        res["\(key).\($0.key)"] = $0.value
                    }
                }
            } else {
                res[key] = value.toString()
            }
        }
    return res
}

/// Given a credential pack, it returns a triple with the credential id, title and issuer.
/// - Parameter credentialPack: the credential pack with credentials
/// - Parameter credential: optional credential parameter
/// - Returns: a triple of strings (id, title, issuer)
func getCredentialIdTitleAndIssuer(
    credentialPack: CredentialPack, credential: ParsedCredential? = nil
) -> (String, String, String) {
    let claims = credentialPack.findCredentialClaims(claimNames: [
        "name", "type", "issuer", "issuing_authority",
    ])

    var cred: Dictionary<Uuid, [String: GenericJSON]>.Element?
    if credential != nil {
        cred = claims.first(where: {
            return $0.key == credential!.id()
        })
    } else {
        cred = claims.first(where: {
            let credential = credentialPack.get(credentialId: $0.key)
            return credential?.asJwtVc() != nil
                || credential?.asJsonVc() != nil
                || credential?.asSdJwt() != nil
        })
    }
    // dc+sd-jwt: use vct for display name
    if cred == nil || credential?.asDcSdJwt() != nil {
        cred =
            claims
            .first(where: {
                return credentialPack.get(credentialId: $0.key)?.asDcSdJwt()
                    != nil
            }).map { claim in
                var tmpClaim = claim
                if let issuingAuthority = claim.value["issuing_authority"],
                   !issuingAuthority.toString().isEmpty {
                    tmpClaim.value["issuer"] = issuingAuthority
                }
                if let dcSdJwt = credentialPack.get(credentialId: claim.key)?.asDcSdJwt() {
                    tmpClaim.value["name"] = GenericJSON.string(
                        credentialTypeDisplayName(for: dcSdJwt.vct()))
                }
                return tmpClaim
            } ?? cred
    }
    // Mdoc: use doctype for display name
    if credential?.asMsoMdoc() != nil || cred == nil {
        cred =
            claims
            .first(where: {
                return credentialPack.get(credentialId: $0.key)?.asMsoMdoc()
                    != nil
            }).map { claim in
                var tmpClaim = claim
                if let issuingAuthority = claim.value["issuing_authority"],
                   !issuingAuthority.toString().isEmpty {
                    tmpClaim.value["issuer"] = issuingAuthority
                }
                if let mdoc = credentialPack.get(credentialId: claim.key)?.asMsoMdoc() {
                    tmpClaim.value["name"] = GenericJSON.string(
                        credentialTypeDisplayName(for: mdoc.doctype()))
                }
                return tmpClaim
            }
    }

    let credentialKey = cred.map { $0.key } ?? ""
    let credentialValue = cred.map { $0.value } ?? [:]

    var title = credentialValue["name"]?.toString()
    if title == nil {
        credentialValue["type"]?.arrayValue?.forEach {
            if $0.toString() != "VerifiableCredential" {
                title = $0.toString().camelCaseToWords()
                return
            }
        }
    }

    var issuer = ""
    if let issuerName = credentialValue["issuer"]?.dictValue?["name"]?
        .toString()
    {
        issuer = issuerName
    } else if let issuerId = credentialValue["issuer"]?.dictValue?["id"]?
        .toString()
    {
        issuer = issuerId
    } else if let issuerId = credentialValue["issuer"]?.toString() {
        issuer = issuerId
    }

    return (credentialKey, title ?? "", issuer)
}

// MARK: - Credential Type Display Name Mapping

/// Known credential type identifier to display name mappings.
/// Used for both mdoc doctypes and dc+sd-jwt vct values, since they share
/// the same namespace (e.g., EUDI types like "eu.europa.ec.eudi.hiid.1").
private let knownCredentialTypeDisplayNames: [String: String] = [
    "org.iso.18013.5.1.mDL": "Mobile Driver's License",
    "org.iso.23220.photoID.1": "Photo ID",
    "org.iso.7367.1.mVRC": "Mobile Vehicle Registration Certificate",
    "eu.europa.ec.eudi.pid.1": "EU Personal ID",
    "eu.europa.ec.av.1": "Age Verification",
    "eu.europa.ec.eudi.msisdn.1": "Phone Number ID",
    "eu.europa.ec.eudi.hiid.1": "Health Insurance ID",
    "eu.europa.ec.eudi.taxid.1": "Tax ID",
    "eu.europa.ec.eudi.cor.1": "Certificate of Residence",
]

/// Returns a human-readable display name for a credential type identifier.
/// Works with mdoc doctypes and dc+sd-jwt vct values.
/// Falls back to generating a readable name from the identifier string if unknown.
func credentialTypeDisplayName(for typeIdentifier: String) -> String {
    if let knownName = knownCredentialTypeDisplayNames[typeIdentifier] {
        return knownName
    }
    return humanizeTypeIdentifier(typeIdentifier)
}

/// Generates a human-readable name from an unknown type identifier.
/// Example: "eu.europa.ec.eudi.hiid.1" -> "Hiid"
private func humanizeTypeIdentifier(_ typeIdentifier: String) -> String {
    let components = typeIdentifier.split(separator: ".")
    guard components.count >= 2 else { return typeIdentifier }

    // Get the second-to-last component (skip version number)
    let meaningfulComponent: String
    if let last = components.last, last.allSatisfy({ $0.isNumber }) {
        meaningfulComponent = String(components[components.count - 2])
    } else {
        meaningfulComponent = String(components.last!)
    }

    return meaningfulComponent
        .replacingOccurrences(of: "_", with: " ")
        .split(separator: " ")
        .map { $0.prefix(1).uppercased() + $0.dropFirst().lowercased() }
        .joined(separator: " ")
}
