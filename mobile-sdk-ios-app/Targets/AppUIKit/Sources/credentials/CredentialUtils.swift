import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

// Get credential and throws error if can't parse
func credentialDisplayerSelector(rawCredential: String, onDelete: (() -> Void)? = nil) throws
  -> any ICredentialView
{
  return GenericCredentialItem(
    credentialPack: try addCredential(
      credentialPack: CredentialPack(), rawCredential: rawCredential),
    onDelete: onDelete
  )
  /* This is temporarily commented on until we define the specific AchievementCredentialItem design */
  //    do {
  //        // Test if it is SdJwt
  //        let credentialPack = CredentialPack()
  //        _ = try credentialPack.addSdJwt(sdJwt: Vcdm2SdJwt.newFromCompactSdJwt(input: rawCredential))
  //        return AchievementCredentialItem(credentialPack: credentialPack, onDelete: onDelete)
  //    } catch {}
  //
  //    do {
  //    return GenericCredentialItem(
  //        credentialPack: try addCredential(credentialPack: CredentialPack(), rawCredential: rawCredential),
  //        onDelete: onDelete
  //    )
  //    } catch {
  //        throw error
  //    }
}

func addCredential(credentialPack: CredentialPack, rawCredential: String) throws -> CredentialPack {
  if let _ = try? credentialPack.addJwtVc(jwtVc: JwtVc.newFromCompactJws(jws: rawCredential)) {
  } else if let _ = try? credentialPack.addJsonVc(
    jsonVc: JsonVc.newFromJson(utf8JsonString: rawCredential))
  {
  } else if let _ = try? credentialPack.addSdJwt(
    sdJwt: Vcdm2SdJwt.newFromCompactSdJwt(input: rawCredential))
  {
  } else if let _ = try? credentialPack.addMDoc(
    mdoc: Mdoc.fromStringifiedDocument(
      stringifiedDocument: rawCredential, keyAlias: UUID().uuidString))
  {
  } else {
    throw CredentialError.parsingError("Couldn't parse credential: \(rawCredential)")
  }
  return credentialPack
}

func credentialHasType(credentialPack: CredentialPack, credentialType: String) -> Bool {
  let credentialTypes = credentialPack.findCredentialClaims(claimNames: ["type"])
  let credentialWithType = credentialTypes.first(where: { credential in
    credential.value["type"]?.arrayValue?.contains(where: { type in
      type.toString().lowercased() == credentialType.lowercased()
    }) ?? false
  })
  return credentialWithType != nil ? true : false
}

func genericObjectFlattener(object: [String: GenericJSON], filter: [String] = []) -> [String:
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
          genericObjectFlattener(object: ["\(idx)": item], filter: filter)
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
