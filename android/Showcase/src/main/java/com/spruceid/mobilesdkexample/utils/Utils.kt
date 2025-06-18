package com.spruceid.mobilesdkexample.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.rs.Cwt
import com.spruceid.mobile.sdk.rs.JsonVc
import com.spruceid.mobile.sdk.rs.JwtVc
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.Uuid
import com.spruceid.mobile.sdk.rs.Vcdm2SdJwt
import com.spruceid.mobilesdkexample.credentials.ICredentialView
import com.spruceid.mobilesdkexample.credentials.genericCredentialItem.GenericCredentialItem
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.sql.Date
import java.text.SimpleDateFormat

val trustedDids = emptyList<String>()

fun getCurrentSqlDate(): Date {
    val currentTimeMillis = System.currentTimeMillis()
    return Date(currentTimeMillis)
}

fun formatSqlDateTime(sqlDate: Date): String {
    val formatter = SimpleDateFormat("MMM dd, yyyy 'at' h:mm a")
    return formatter.format(sqlDate)
}

fun String.splitCamelCase() = replace(
    String.format(
        "%s|%s|%s",
        "(?<=[A-Z])(?=[A-Z][a-z])",
        "(?<=[^A-Z])(?=[A-Z])",
        "(?<=[A-Za-z])(?=[^A-Za-z])"
    ).toRegex(), " "
)
    .replace("\\s+".toRegex(), " ")
    .replaceFirstChar(Char::titlecase)

fun String.removeUnderscores() = replace("_", "")

fun String.removeCommas() = replace(",", "")

fun String.removeEscaping() = replace("\\/", "/")

fun String.isDate(): Boolean {
    return lowercase().contains("date") ||
            lowercase().contains("from") ||
            lowercase().contains("until")
}

fun String.isImage(): Boolean {
    return lowercase().contains("image") ||
            (lowercase().contains("portrait") && !lowercase().contains("date")) ||
            contains("data:image")
}

@Composable
fun BitmapImage(
    byteArray: ByteArray,
    contentDescription: String,
    modifier: Modifier,
) {
    fun convertImageByteArrayToBitmap(imageData: ByteArray): Bitmap {
        return BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
    }

    val bitmap = convertImageByteArrayToBitmap(byteArray)

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}

fun checkAndRequestBluetoothPermissions(
    context: Context,
    permissions: Array<String>,
    launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
) {
    if (
        permissions.all {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }
    ) {
        // Use bluetooth because permissions are already granted
    } else {
        // Request permissions
        launcher.launch(permissions)
    }
}

fun keyPathFinder(json: Any, path: MutableList<String>): Any {
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

fun credentialDisplaySelector(
    rawCredential: String,
    statusListViewModel: StatusListViewModel,
    goTo: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onExport: ((String) -> Unit)?,
): ICredentialView {
    return GenericCredentialItem(
        rawCredential,
        statusListViewModel,
        goTo,
        onDelete,
        onExport,
    )
}

fun credentialDisplaySelector(
    credentialPack: CredentialPack,
    statusListViewModel: StatusListViewModel,
    goTo: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onExport: ((String) -> Unit)?,
): ICredentialView {
    return GenericCredentialItem(
        credentialPack,
        statusListViewModel,
        goTo,
        onDelete,
        onExport
    )
}

fun addCredential(credentialPack: CredentialPack, rawCredential: String): CredentialPack {
    try {
        credentialPack.addJsonVc(JsonVc.newFromJson(rawCredential))
        return credentialPack
    } catch (_: Exception) {
    }

    try {
        credentialPack.addSdJwt(Vcdm2SdJwt.newFromCompactSdJwt(rawCredential))
        return credentialPack
    } catch (_: Exception) {
    }

    try {
        credentialPack.addJwtVc(JwtVc.newFromCompactJws(rawCredential))
        return credentialPack
    } catch (_: Exception) {
    }

    try {
        credentialPack.addMdoc(Mdoc.fromStringifiedDocument(rawCredential, keyAlias = Uuid()))
        return credentialPack
    } catch (_: Exception) {
    }

    try {
        credentialPack.addMdoc(
            Mdoc.newFromBase64urlEncodedIssuerSigned(
                rawCredential,
                keyAlias = Uuid()
            )
        )
        return credentialPack
    } catch (_: Exception) {
    }

    try {
        credentialPack.addCwt(Cwt.newFromBase10(rawCredential))
        return credentialPack
    } catch (_: Exception) {
    }

    println("Couldn't parse credential $rawCredential")

    return credentialPack
}

fun getFileContent(credentialPack: CredentialPack): String {
    val rawCredentials = mutableListOf<String>()
    val claims = credentialPack.findCredentialClaims(listOf())

    credentialPack.list().forEach { parsedCredential ->
        if (parsedCredential.asSdJwt() != null) {
            rawCredentials.add(
                envelopVerifiableSdJwtCredential(
                    String(parsedCredential.intoGenericForm().payload)
                )
            )
        } else {
            claims[parsedCredential.id()].let {
                if (it != null) {
                    rawCredentials.add(it.toString(4).removeEscaping())
                }
            }
        }
    }
    return rawCredentials.first()
}

fun envelopVerifiableSdJwtCredential(sdJwt: String): String {
    val jsonString = """ 
        {
          "@context": ["https://www.w3.org/ns/credentials/v2"],
          "type": ["EnvelopedVerifiableCredential"],
          "id": "data:application/vc+sd-jwt,$sdJwt"
        }
        """
    try {
        val jsonObject = JSONObject(jsonString)
        val prettyPrinted = jsonObject.toString(4)
        return prettyPrinted.removeEscaping()
    } catch (e: Exception) {
        return jsonString.removeEscaping()
    }
}

/**
 * Given a credential pack, it returns a triple with the credential id, title and issuer.
 * @param credentialPack the credential pack with credentials
 * @param credential optional credential parameter
 *
 * @return a triple of strings Triple<id, title, issuer>
 */
fun getCredentialIdTitleAndIssuer(
    credentialPack: CredentialPack,
    credential: ParsedCredential? = null
): Triple<String, String, String> {
    val claims =
        credentialPack.findCredentialClaims(listOf("name", "type", "issuer", "issuing_authority"))

    val cred = if (credential != null) {
        claims.entries.firstNotNullOf { claim ->
            if (claim.key == credential.id()) {
                claim
            } else {
                null
            }
        }
    } else {
        claims.entries.firstNotNullOf { claim ->
            val c = credentialPack.getCredentialById(claim.key)
            val mdoc = c?.asMsoMdoc()
            if (
                c?.asSdJwt() != null ||
                c?.asJwtVc() != null ||
                c?.asJsonVc() != null
            ) {
                claim
            } else if (mdoc != null) {
                // Assume mDL.
                val issuer = claim.value.get("issuing_authority")
                claim.value.put("issuer", issuer)
                val title = "Mobile Drivers License"
                claim.value.put("name", title)
                claim
            } else {
                null
            }
        }
    }

    val credentialKey = cred.key
    val credentialValue = cred.value

    var title = ""
    try {
        title = credentialValue.get("name").toString()
        if (title.isBlank()) {
            val arrayTypes = credentialValue.getJSONArray("type")
            for (i in 0 until arrayTypes.length()) {
                if (arrayTypes.get(i).toString() != "VerifiableCredential") {
                    title = arrayTypes.get(i).toString().splitCamelCase()
                    break
                }
            }
        }
    } catch (_: Exception) {
    }

    var issuer = ""
    try {
        issuer = credentialValue.getJSONObject("issuer").getString("name").toString()
    } catch (_: Exception) {
    }

    if (issuer.isBlank()) {
        try {
            issuer = credentialValue.getJSONObject("issuer").getString("id").toString()
        } catch (_: Exception) {
        }
    }

    if (issuer.isBlank()) {
        try {
            issuer = credentialValue.getString("issuer")
        } catch (_: Exception) {
        }
    }

    return Triple(credentialKey, title, issuer)
}

fun jsonArrayToByteArray(jsonArray: JSONArray): ByteArray {
    val byteList = mutableListOf<Byte>()
    for (i in 0 until jsonArray.length()) {
        byteList.add(jsonArray.getInt(i).toByte())
    }
    return byteList.toByteArray()
}

fun credentialPackHasMdoc(credentialPack: CredentialPack): Boolean {
    credentialPack.list().forEach { credential ->
        try {
            if (credential.asMsoMdoc() != null) {
                return true
            }
        } catch (_: Exception) {
        }
    }
    return false
}