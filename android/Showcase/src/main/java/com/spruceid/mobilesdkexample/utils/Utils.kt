package com.spruceid.mobilesdkexample.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialsViewModel
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
    launcher: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>,
    credentialViewModel: CredentialsViewModel? = null,
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
        credentialViewModel?.setBluetoothPermissionsGranted(true)
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

    var cred = if (credential != null) {
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
    // Assume mDL.
    if (credential?.asMsoMdoc() != null || cred.equals(null)) {
        cred = claims.entries.firstNotNullOf { claim ->
            val issuer = claim.value.get("issuing_authority")
            claim.value.put("issuer", issuer)
            val title = "Mobile Drivers License"
            claim.value.put("name", title)
            claim
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

// Field name translation and sorting utilities
data class FieldMetadata(val displayName: String, val sortOrder: Int)

private val FIELD_METADATA = mapOf(
    "portrait" to FieldMetadata("Portrait", 0),
    "document_number" to FieldMetadata("Document Number", 1),
    "document number" to FieldMetadata("Document Number", 1),
    "given_name" to FieldMetadata("First Name", 2),
    "given name" to FieldMetadata("First Name", 2),
    "first name" to FieldMetadata("First Name", 2),
    "family_name" to FieldMetadata("Last Name", 3),
    "family name" to FieldMetadata("Last Name", 3),
    "last name" to FieldMetadata("Last Name", 3),
    "birth_date" to FieldMetadata("Date of Birth", 4),
    "birth date" to FieldMetadata("Date of Birth", 4),
    "date of birth" to FieldMetadata("Date of Birth", 4),
    "issue_date" to FieldMetadata("Issuance Date", 5),
    "issue date" to FieldMetadata("Issuance Date", 5),
    "issuance date" to FieldMetadata("Issuance Date", 5),
    "expiry_date" to FieldMetadata("Expiration Date", 6),
    "expiry date" to FieldMetadata("Expiration Date", 6),
    "expiration date" to FieldMetadata("Expiration Date", 6),
    "issuing_country" to FieldMetadata("Country", 7),
    "issuing country" to FieldMetadata("Country", 7),
    "country" to FieldMetadata("Country", 7),
    "issuing_authority" to FieldMetadata("Issuer", 8),
    "issuing authority" to FieldMetadata("Issuer", 8),
    "authority" to FieldMetadata("Issuer", 8),
    "sex" to FieldMetadata("Sex", 9),
    "height" to FieldMetadata("Height", 10),
    "weight" to FieldMetadata("Weight", 11),
    "eye_colour" to FieldMetadata("Eye Color", 12),
    "eye color" to FieldMetadata("Eye Color", 12),
    "hair_colour" to FieldMetadata("Hair Color", 13),
    "hair color" to FieldMetadata("Hair Color", 13),
    "nationality" to FieldMetadata("Nationality", 14),
    "resident_address" to FieldMetadata("Address", 15),
    "address" to FieldMetadata("Address", 15),
    "resident_city" to FieldMetadata("City", 16),
    "city" to FieldMetadata("City", 16),
    "resident_state" to FieldMetadata("State", 17),
    "state" to FieldMetadata("State", 17),
    "resident_postal_code" to FieldMetadata("Postal Code", 18),
    "postal code" to FieldMetadata("Postal Code", 18),
    "phone_number" to FieldMetadata("Phone Number", 19),
    "phone" to FieldMetadata("Phone Number", 19),
    "email_address" to FieldMetadata("Email Address", 20),
    "email" to FieldMetadata("Email Address", 20),
    "driving_privileges" to FieldMetadata("Driving Privileges", 21),
    "domestic_driving_privileges" to FieldMetadata("Domestic Driving Privileges", 22),
    "vehicle_class" to FieldMetadata("Vehicle Class", 23),
    "restrictions" to FieldMetadata("Restrictions", 24),
    "endorsements" to FieldMetadata("Endorsements", 25),
    "age_over_18" to FieldMetadata("Age Over 18", 26),
    "age_over_21" to FieldMetadata("Age Over 21", 27),
    "organ_donor" to FieldMetadata("Organ Donor", 28),
    "veteran" to FieldMetadata("Veteran", 29),
    "real_id" to FieldMetadata("Real ID", 30),
    "dhs_compliance" to FieldMetadata("DHS Compliance", 31),
    "jurisdiction_version" to FieldMetadata("Jurisdiction Version", 32),
    "jurisdiction_id" to FieldMetadata("Jurisdiction ID", 33)
)

fun getFieldDisplayName(fieldName: String): String {
    return FIELD_METADATA[fieldName.lowercase()]?.displayName
        ?: fieldName.replace("_", " ").split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
}

fun getFieldSortOrder(fieldName: String): Int {
    return FIELD_METADATA[fieldName.lowercase()]?.sortOrder ?: 999
}

enum class CredentialFieldType {
    TEXT,
    DATE,
    IMAGE
}

fun getCredentialFieldType(displayName: String, fieldValue: String = ""): CredentialFieldType {
    return when (displayName) {
        "Portrait" -> CredentialFieldType.IMAGE
        else -> {
            val lowerDisplayName = displayName.lowercase()
            val lowerFieldValue = fieldValue.lowercase()
            when {
                lowerDisplayName.contains("portrait") ||
                        lowerDisplayName.contains("image") ||
                        lowerDisplayName.contains("photo") -> CredentialFieldType.IMAGE
                // Check if field value contains "date" or looks like a date format
                lowerFieldValue.contains("date") ||
                        fieldValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z?")) ||
                        fieldValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> CredentialFieldType.DATE

                else -> CredentialFieldType.TEXT
            }
        }
    }
}

private fun formatDateValue(fieldValue: String): String? {
    return try {
        when {
            // Handle ISO 8601 format with time and timezone (e.g., "2024-01-15T10:30:00Z")
            fieldValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z?")) -> {
                val inputFormat = if (fieldValue.endsWith("Z")) {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                } else {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                }
                val outputFormat = SimpleDateFormat("MMM dd, yyyy")
                val date = inputFormat.parse(fieldValue)
                date?.let { outputFormat.format(it) }
            }
            // Handle simple date format (e.g., "2024-01-15")
            fieldValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd")
                val outputFormat = SimpleDateFormat("MMM dd, yyyy")
                val date = inputFormat.parse(fieldValue)
                date?.let { outputFormat.format(it) }
            }

            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

fun formatCredentialFieldValue(
    fieldValue: String,
    fieldType: CredentialFieldType,
    fieldName: String = ""
): String {
    return when (fieldType) {
        CredentialFieldType.DATE -> {
            formatDateValue(fieldValue) ?: fieldValue
        }

        CredentialFieldType.TEXT -> {
            // First, check if the value looks like a date format
            formatDateValue(fieldValue)?.let { return it }

            // Handle different text types based on field name
            when {
                // Handle sex field specifically
                fieldName.lowercase() == "sex" -> {
                    when (fieldValue) {
                        "1" -> "M"
                        "2" -> "F"
                        "M", "m", "male" -> "M"
                        "F", "f", "female" -> "F"
                        else -> fieldValue.uppercase()
                    }
                }
                // Handle booleans - text format
                fieldValue.equals("true", ignoreCase = true) -> "True"
                fieldValue.equals("false", ignoreCase = true) -> "False"
                // Handle regular text - convert to title case and truncate if needed
                else -> {
                    val titleCaseValue = fieldValue.split(" ").joinToString(" ") { word ->
                        word.replaceFirstChar { char ->
                            if (char.isLowerCase()) char.titlecase() else char.toString()
                        }
                    }
                    if (titleCaseValue.length > 20) "${titleCaseValue.take(17)}..." else titleCaseValue
                }
            }
        }

        CredentialFieldType.IMAGE -> {
            // For images, return empty string as we'll handle the image display separately
            ""
        }
    }
}

@Composable
fun RenderCredentialFieldValue(
    fieldType: CredentialFieldType,
    rawFieldValue: String,
    formattedValue: String,
    displayName: String
) {
    when (fieldType) {
        CredentialFieldType.IMAGE -> {
            // Display the actual portrait image
            if (rawFieldValue.isNotEmpty()) {
                val bitmap = remember(rawFieldValue) {
                    try {
                        // Remove data URL prefix if present (e.g., "data:image/jpeg;base64,")
                        val cleanBase64 = if (rawFieldValue.startsWith("data:")) {
                            rawFieldValue.substringAfter("base64,")
                        } else {
                            rawFieldValue
                        }
                        val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }

                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = displayName,
                        modifier = Modifier
                            .size(75.dp, 75.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Color.Black.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    )
                }
            }
        }

        else -> {
            // Show the formatted field value for text and dates
            if (formattedValue.isNotEmpty()) {
                Text(
                    text = formattedValue,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = ColorStone600,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}