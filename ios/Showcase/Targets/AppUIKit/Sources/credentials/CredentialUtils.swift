import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

// Get credential and throws error if can't parse
func credentialDisplayerSelector(
    rawCredential: String,
    goTo: (() -> Void)? = nil,
    onDelete: (() -> Void)? = nil
) throws
    -> any ICredentialView
{
    return GenericCredentialItem(
        credentialPack: try addCredential(
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

func addCredential(credentialPack: CredentialPack, rawCredential: String) throws
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
    } else if (try? credentialPack.addCwt(
        cwt: Cwt.newFromBase10(payload: rawCredential))) != nil
    {
    } else if (try? credentialPack.addMDoc(
        mdoc: Mdoc.fromStringifiedDocument(
            stringifiedDocument: rawCredential, keyAlias: UUID().uuidString)))
        != nil
    {
    } else if (try? credentialPack.addMDoc(
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
        // Mdoc
        if cred == nil {
            cred =
                claims
                .first(where: {
                    return credentialPack.get(credentialId: $0.key)?.asMsoMdoc()
                        != nil
                }).map { claim in
                    var tmpClaim = claim
                    tmpClaim.value["issuer"] = claim.value["issuing_authority"]
                    tmpClaim.value["name"] = GenericJSON.string(
                        "Mobile Drivers License")
                    return tmpClaim
                }
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
