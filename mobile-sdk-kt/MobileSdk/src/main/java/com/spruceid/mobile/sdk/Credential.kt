package com.spruceid.mobile.sdk

import com.spruceid.mobile.sdk.rs.Cwt
import com.spruceid.mobile.sdk.rs.JsonVc
import com.spruceid.mobile.sdk.rs.JwtVc
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.Vcdm2SdJwt
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Access all of the elements in the mdoc, ignoring namespaces and missing elements that cannot be encoded as JSON.
 */
fun Mdoc.jsonEncodedDetailsAll(): JSONObject = this.jsonEncodedDetailsInternal(null)

/**
 * Access the specified elements in the mdoc, ignoring namespaces and missing elements that cannot be encoded as JSON.
 */
fun Mdoc.jsonEncodedDetailsFiltered(elementIdentifiers: List<String>): JSONObject = this.jsonEncodedDetailsInternal(elementIdentifiers)


private fun Mdoc.jsonEncodedDetailsInternal(elementIdentifiers: List<String>?): JSONObject =
    JSONObject(
        // Ignore the namespaces.
        this.details().values.flatMap { elements ->
            elements.map { element ->
                val id = element.identifier
                val jsonString = element.value

                // If a filter is provided, filter out non-specified ids.
                if (elementIdentifiers != null) {
                    if (!elementIdentifiers.contains(id)) {
                        return@map null
                    }
                }

                if (jsonString != null) {
                    try {
                        val jsonElement = JSONTokener(jsonString).nextValue()
                        return@map Pair(id, jsonElement)
                    } catch (e: JSONException) {
                        print("failed to decode '$id' as JSON: $e")
                    }
                }

                return@map null
            }
        }.filterNotNull().toMap()
    )

/**
 * Access the W3C VCDM credential (not including the JWT envelope).
 */
fun JwtVc.credentialClaims(): JSONObject {
    try {
        return JSONObject(this.credentialAsJsonEncodedUtf8String())
    } catch (e: Error) {
        print("failed to decode VCDM data from UTF-8-encoded JSON")
        return JSONObject()
    }
}

/**
 * Access the specified claims from the W3C VCDM credential (not including the JWT envelope).
 */
fun JwtVc.credentialClaimsFiltered(claimNames: List<String>): JSONObject {
    val old = this.credentialClaims()
    val new = JSONObject()
    for (name in claimNames) {
        if (old.has(name)) {
            new.put(name, keyPathFinder(old, name.split(".").toMutableList()))
        }
    }
    return new
}

/**
 * Access the W3C VCDM credential.
 */
fun JsonVc.credentialClaims(): JSONObject {
    try {
        return JSONObject(this.credentialAsJsonEncodedUtf8String())
    } catch (e: Error) {
        print("failed to decode VCDM data from UTF-8-encoded JSON")
        return JSONObject()
    }
}

/**
 * Access the specified claims from the W3C VCDM credential.
 */
fun JsonVc.credentialClaimsFiltered(claimNames: List<String>): JSONObject {
    val old = this.credentialClaims()
    val new = JSONObject()
    for (name in claimNames) {
        new.put(name, keyPathFinder(old, name.split(".").toMutableList()))
    }
    return new
}

/**
 * Access the CWT credential.
 */
fun Cwt.credentialClaims(): JSONObject {
    try {
        val jsonObject = JSONObject()
        this.claims().forEach { (key, cborValue) ->
            jsonObject.put(key, cborValue.toText())
        }
        return jsonObject
    } catch (e: Error) {
        print("failed to decode CWT data from UTF-8-encoded JSON")
        return JSONObject()
    }
}

/**
 * Access the specified claims from the CWT credential.
 */
fun Cwt.credentialClaimsFiltered(claimNames: List<String>): JSONObject {
    val old = this.credentialClaims()
    val new = JSONObject()
    for (name in claimNames) {
        new.put(name, keyPathFinder(old, name.split(".").toMutableList()))
    }
    return new
}

/**
 * Access the VCDM 2.0 SD-JWT credential.
 */
fun Vcdm2SdJwt.credentialClaims(): JSONObject {
    try {
        return JSONObject(this.revealedClaimsAsJsonString())
    } catch (e: Error) {
        print("failed to decode SD-JWT data from UTF-8-encoded JSON")
        return JSONObject()
    }
}

/**
 * Access the specified claims from the VCDM 2.0 SD-JWT credential.
 */
fun Vcdm2SdJwt.credentialClaimsFiltered(claimNames: List<String>): JSONObject {
    val old = this.credentialClaims()
    val new = JSONObject()
    for (name in claimNames) {
        new.put(name, keyPathFinder(old, name.split(".").toMutableList()))
    }
    return new
}

private fun keyPathFinder(json: Any, path: MutableList<String>): Any {
    try {
        val firstKey = path.first()
        val element = (json as JSONObject)[firstKey]
        path.removeAt(0)
        if (path.isNotEmpty()) {
            return keyPathFinder(element, path)
        }
        return element
    } catch (e: Exception) {
        return ""
    }
}