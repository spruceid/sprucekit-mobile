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
import com.spruceid.mobile.sdk.rs.JsonVc
import com.spruceid.mobile.sdk.rs.JwtVc
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.Uuid
import com.spruceid.mobile.sdk.rs.Vcdm2SdJwt
import com.spruceid.mobilesdkexample.credentials.GenericCredentialItem
import com.spruceid.mobilesdkexample.credentials.ICredentialView
import org.json.JSONObject
import java.sql.Date
import java.text.SimpleDateFormat

const val keyPEM =
    "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgEAqKZdZQgPVtjlEB\nfz2ItHG8oXIONenOxRePtqOQ42yhRANCAATA43gI2Ib8+qKK4YEOfNCRiNOhyHaC\nLgAvKdhHS+y6wpG3oJ2xudXagzKKbcfvUda4x0j8zR1/oD56mpm85GbO\n-----END PRIVATE KEY-----\n-----BEGIN CERTIFICATE-----\nMIICgDCCAiWgAwIBAgIUTp04dh8m8Vxa/hX5LmTvjSWrAS8wCgYIKoZIzj0EAwIw\ngZQxCzAJBgNVBAYTAlVTMREwDwYDVQQIDAhOZXcgWW9yazERMA8GA1UEBwwITmV3\nIFlvcmsxEjAQBgNVBAoMCVNwcnVjZSBJRDESMBAGA1UECwwJU3BydWNlIElkMRIw\nEAYDVQQDDAlTcHJ1Y2UgSUQxIzAhBgkqhkiG9w0BCQEWFGNvbnRhY3RAc3BydWNl\naWQuY29tMB4XDTI0MDIxMjE2NTEwMVoXDTI1MDIxMTE2NTEwMVowgZQxCzAJBgNV\nBAYTAlVTMREwDwYDVQQIDAhOZXcgWW9yazERMA8GA1UEBwwITmV3IFlvcmsxEjAQ\nBgNVBAoMCVNwcnVjZSBJRDESMBAGA1UECwwJU3BydWNlIElkMRIwEAYDVQQDDAlT\ncHJ1Y2UgSUQxIzAhBgkqhkiG9w0BCQEWFGNvbnRhY3RAc3BydWNlaWQuY29tMFkw\nEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwON4CNiG/PqiiuGBDnzQkYjToch2gi4A\nLynYR0vsusKRt6CdsbnV2oMyim3H71HWuMdI/M0df6A+epqZvORmzqNTMFEwHQYD\nVR0OBBYEFPbjKnGAa0aSXw0oe4KfHdN5M1ssMB8GA1UdIwQYMBaAFPbjKnGAa0aS\nXw0oe4KfHdN5M1ssMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIh\nAO2msc7LSdakGcw3q7DxEySqzepr+LeWWNvPbQypQxd8AiEAj7dVI3V00gq3K3OU\nCbkeKnYiGtVCZnXnR/MW91mPeGE=\n-----END CERTIFICATE-----"

const val keyBase64 =
    "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgEAqKZdZQgPVtjlEBfz2ItHG8oXIONenOxRePtqOQ42yhRANCAATA43gI2Ib8+qKK4YEOfNCRiNOhyHaCLgAvKdhHS+y6wpG3oJ2xudXagzKKbcfvUda4x0j8zR1/oD56mpm85GbO"

val trustedDids = MutableList(1) { "did:web:companion.ler-sandbox.spruceid.xyz:oid4vp:client" }

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
    .replaceFirstChar(Char::titlecase)

fun String.removeUnderscores() = replace("_", "")

fun String.removeCommas() = replace(",", "")


fun String.isDate(): Boolean {
    return lowercase().contains("date") ||
            lowercase().contains("from") ||
            lowercase().contains("until")
}

fun String.isImage(): Boolean {
    return lowercase().contains("image") ||
            lowercase().contains("portrait") ||
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
    onDelete: (() -> Unit)?,
    onExport: ((String) -> Unit)?
): ICredentialView {
    /* This is temporarily commented on until we define the specific AchievementCredentialItem design */
//        try {
//                 Test if it is SdJwt
//                val credentialPack = CredentialPack()
//                credentialPack.addSdJwt(Vcdm2SdJwt.newFromCompactSdJwt(rawCredential))
//                return AchievementCredentialItem(credentialPack, onDelete)
//        } catch (_: Exception) {
    return GenericCredentialItem(rawCredential, onDelete, onExport)
//        }
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

    println("Couldn't parse credential $rawCredential")

    return credentialPack
}

fun getFileContent(credentialPack: CredentialPack): String {
    val rawCredentials = mutableListOf<String>()
    val claims = credentialPack.findCredentialClaims(listOf())

    claims.keys.forEach { key ->
        claims[key].let {
            if (it != null) {
                rawCredentials.add(it.toString(4))
            }
        }
    }
    return rawCredentials.first() ?: ""
}
