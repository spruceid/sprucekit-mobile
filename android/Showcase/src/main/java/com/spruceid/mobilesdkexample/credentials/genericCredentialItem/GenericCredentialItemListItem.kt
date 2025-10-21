package com.spruceid.coloradofwd.credentials.genericCredentialItem

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobile.sdk.jsonEncodedDetailsAll
import com.spruceid.mobile.sdk.ui.basecard.BaseCard
import com.spruceid.mobile.sdk.ui.basecard.CardRenderingListView
import com.spruceid.mobile.sdk.ui.basecard.CardStyle
import com.spruceid.mobile.sdk.ui.basecard.toCardRendering
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.CredentialImage
import com.spruceid.mobilesdkexample.credentials.CredentialOptionsDialogActions
import com.spruceid.mobilesdkexample.credentials.CredentialStatusSmall
import com.spruceid.mobilesdkexample.credentials.FullSizeCredentialImage
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import org.json.JSONObject

@Composable
fun GenericCredentialItemListItem(
    statusListViewModel: StatusListViewModel,
    credentialPack: CredentialPack,
    onDelete: (() -> Unit)?,
    onExport: ((String) -> Unit)?,
    withOptions: Boolean
) {
    Column(
        Modifier
            .padding(bottom = 15.dp)
    ) {
        GenericCredentialListItem(
            statusListViewModel = statusListViewModel,
            credentialPack = credentialPack,
            withOptions = withOptions,
            onDelete = onDelete,
            onExport = onExport
        )
    }
}

@Composable
fun GenericCredentialListItemDescriptionFormatter(
    statusListViewModel: StatusListViewModel,
    credentialPack: CredentialPack,
    values: Map<String, JSONObject>
) {
    val statusList by statusListViewModel.observeStatusForId(credentialPack.id())
        .collectAsState()
    Log.d("STATUS LIST", statusList.toString())
    val credential = values.toList().firstNotNullOfOrNull {
        val cred = credentialPack.getCredentialById(it.first)
        val mdoc = cred?.asMsoMdoc()
        try {
            if (
                cred?.asJwtVc() != null ||
                cred?.asJsonVc() != null ||
                cred?.asSdJwt() != null
            ) {
                it.second
            } else if (mdoc != null) {
                // Assume mDL.
                val details = mdoc.jsonEncodedDetailsAll()
                it.second.put("issuer", details.get("issuing_authority"))
                it.second
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    var description = ""
    try {
        description = credential?.getJSONObject("issuer")?.getString("name").toString()
    } catch (_: Exception) {
    }

    if (description.isBlank()) {
        try {
            description = credential?.getString("description").toString()
        } catch (_: Exception) {
        }
    }

    if (description.isBlank()) {
        try {
            description = credential?.getString("issuer").toString()
        } catch (_: Exception) {
        }
    }


    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = description,
            fontFamily = Switzer,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = Color.White,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(2.5f, 2.5f),
                    blurRadius = 10f
                )
            )
        )
        CredentialStatusSmall(
            statusList ?: CredentialStatusList.UNDEFINED
        )
    }
}

@Composable
private fun GenericCredentialListItemLeadingIconFormatter(
    credentialPack: CredentialPack,
    values: Map<String, JSONObject>
) {
    val credential = values.toList().firstNotNullOfOrNull {
        val cred = credentialPack.getCredentialById(it.first)
        try {
            if (
                cred?.asJwtVc() != null ||
                cred?.asJsonVc() != null ||
                cred?.asSdJwt() != null
            ) {
                it.second
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    var image = ""
    try {
        val issuerImage = credential?.getJSONObject("issuer.image")
        issuerImage?.optString("image").let {
            if (it != null) {
                image = it.toString()
                return
            }
        }

        issuerImage?.optString("id").let {
            if (it != null) {
                image = it.toString()
                return
            }
        }

    } catch (_: Exception) {
    }

    try {
        image = credential?.getString("issuer.image") ?: ""
    } catch (_: Exception) {
    }

    try {
        image = credential?.getString("credentialSubject.achievement.image.id") ?: ""
    } catch (e: Exception) {
        e.printStackTrace()
    }

    var alt = ""
    try {
        alt = credential?.getString("issuer.name").toString()
    } catch (_: Exception) {
    }

    Column(
        Modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (image.isNotBlank()) {
            CredentialImage(image, alt)
        }
    }
}


@Composable
fun GenericCredentialListItem(
    statusListViewModel: StatusListViewModel,
    credentialPack: CredentialPack,
    withOptions: Boolean,
    onDelete: (() -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    leadingIconFormatter: ((CredentialPack, Map<String, JSONObject>) -> Unit)? = null,
    descriptionFormatter: ((StatusListViewModel, CredentialPack, Map<String, JSONObject>) -> Unit)? = null
) {
    var showBottomSheet by remember { mutableStateOf(false) }

    val listRendering = CardRenderingListView(
        titleKeys = listOf("name", "type", "credentialSubject.name", "id"),
        titleFormatter = { values ->
            titleFormatter(
                credentialPack,
                values,
                showBottomSheet,
                { showBottomSheet = it },
                withOptions,
                onDelete,
                onExport
            )
        },
        descriptionKeys = listOf("description", "issuer"),
        descriptionFormatter = { values ->
            if (descriptionFormatter != null) {
                descriptionFormatter.invoke(
                    statusListViewModel,
                    credentialPack,
                    values
                )
            } else {
                GenericCredentialListItemDescriptionFormatter(
                    statusListViewModel,
                    credentialPack,
                    values
                )
            }

        },
        leadingIconKeys = listOf(
            "issuer.image",
            "issuer.name",
            "type",
            "credentialSubject.achievement.image.id"
        ),
        leadingIconFormatter = { values ->
            if (leadingIconFormatter != null) {
                leadingIconFormatter.invoke(
                    credentialPack,
                    values
                )
            } else {
                GenericCredentialListItemLeadingIconFormatter(credentialPack, values)
            }

        },
        cardStyle = CardStyle(
            topLeftLogoResId = R.drawable.spruce_logo,
            topLeftLogoTint = Color.White,
            backgroundImageResId = R.drawable.credential_bg,
            credentialImageKeys = listOf(
                "portrait",
                "image",
                "issuer.image",
                "credentialSubject.image",
                "credentialSubject.issuer.image",
                "issuer.name",
                "type",
                "credentialSubject.achievement.image.id"
            ),
            credentialImageFormatter = { values ->
                credentialImageFormatter(credentialPack, values)
            }
        )
    )

    BaseCard(
        credentialPack = credentialPack,
        rendering = listRendering.toCardRendering()
    )
}

@Composable
private fun titleFormatter(
    credentialPack: CredentialPack,
    values: Map<String, JSONObject>,
    showBottomSheet: Boolean,
    setShowBottomSheet: (Boolean) -> Unit,
    withOptions: Boolean,
    onDelete: (() -> Unit)?,
    onExport: ((String) -> Unit)?,
) {
    val credential = values.toList().firstNotNullOfOrNull {
        val cred = credentialPack.getCredentialById(it.first)
        try {
            val mdoc = cred?.asMsoMdoc()
            if (
                cred?.asJwtVc() != null ||
                cred?.asJsonVc() != null ||
                cred?.asSdJwt() != null
            ) {
                it.second
            } else if (mdoc != null) {
                // Assume mDL.
                it.second.put("name", "Mobile Drivers License")
                it.second
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
    var title = ""

    // Priority 1: name
    if (title.isBlank()) {
        try {
            val name = credential?.optString("name", "") ?: ""
            if (name.isNotBlank()) {
                title = name
            }
        } catch (_: Exception) {
        }
    }

    // Priority 2: credentialSubject.name
    if (title.isBlank()) {
        try {
            val credSubjectName = credential?.optString("credentialSubject.name", "") ?: ""
            if (credSubjectName.isNotBlank()) {
                title = credSubjectName
            }
        } catch (_: Exception) {
        }
    }

    // Priority 3: type (as array)
    if (title.isBlank()) {
        try {
            val arrayTypes = credential?.getJSONArray("type")
            if (arrayTypes != null) {
                for (i in 0 until arrayTypes.length()) {
                    if (arrayTypes.get(i).toString() != "VerifiableCredential") {
                        title = arrayTypes.get(i).toString().splitCamelCase()
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // Priority 4: id
    if (title.isBlank()) {
        try {
            val id = credential?.optString("id", "") ?: ""
            if (id.isNotBlank()) {
                title = id
            }
        } catch (_: Exception) {
        }
    }

    Column {
        if (withOptions) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Image(
                    painter = painterResource(id = R.drawable.three_dots_horizontal),
                    contentDescription = stringResource(id = R.string.three_dots),
                    modifier = Modifier
                        .width(15.dp)
                        .height(12.dp)
                        .clickable {
                            setShowBottomSheet(true)
                        }
                )
            }
        }
        Text(
            text = title,
            fontFamily = Switzer,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = Color.White,
            modifier = Modifier.padding(bottom = 6.dp),
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(2.5f, 2.5f),
                    blurRadius = 10f
                )
            )
        )
        if (showBottomSheet) {
            CredentialOptionsDialogActions(
                setShowBottomSheet = setShowBottomSheet,
                onDelete = onDelete,
                onExport = {
                    onExport?.let { it(title) }
                }
            )
        }
    }
}

@Composable
private fun credentialImageFormatter(
    credentialPack: CredentialPack,
    values: Map<String, JSONObject>
) {
    val credential = values.toList().firstNotNullOfOrNull {
        val cred = credentialPack.getCredentialById(it.first)
        try {
            if (
                cred?.asJwtVc() != null ||
                cred?.asJsonVc() != null ||
                cred?.asSdJwt() != null ||
                cred?.asMsoMdoc() != null
            ) {
                it.second
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    var image = ""

    // Priority 1: portrait
    if (image.isBlank()) {
        try {
            val portraitImage = credential?.optString("portrait", "") ?: ""
            if (portraitImage.isNotBlank()) {
                image = portraitImage
            }
        } catch (_: Exception) {
        }
    }

    // Priority 2: image
    if (image.isBlank()) {
        try {
            val imageField = credential?.optString("image", "") ?: ""
            if (imageField.isNotBlank()) {
                image = imageField
            }
        } catch (_: Exception) {
        }
    }

    // Priority 3: issuer.image (as object or string)
    if (image.isBlank()) {
        try {
            val issuerImage = credential?.getJSONObject("issuer.image")
            issuerImage?.optString("image")?.let {
                if (it.isNotBlank()) {
                    image = it
                }
            }
            if (image.isBlank()) {
                issuerImage?.optString("id")?.let {
                    if (it.isNotBlank()) {
                        image = it
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (image.isBlank()) {
            try {
                image = credential?.optString("issuer.image", "") ?: ""
            } catch (_: Exception) {
            }
        }
    }

    // Priority 4: credentialSubject.image
    if (image.isBlank()) {
        try {
            val credSubjectImage = credential?.optString("credentialSubject.image", "") ?: ""
            if (credSubjectImage.isNotBlank()) {
                image = credSubjectImage
            }
        } catch (_: Exception) {
        }
    }

    // Priority 5: credentialSubject.issuer.image
    if (image.isBlank()) {
        try {
            val credSubjectIssuerImage =
                credential?.optString("credentialSubject.issuer.image", "") ?: ""
            if (credSubjectIssuerImage.isNotBlank()) {
                image = credSubjectIssuerImage
            }
        } catch (_: Exception) {
        }
    }

    // Priority 6-7: issuer.name and type are handled by the SDK automatically

    // Priority 8: credentialSubject.achievement.image.id
    if (image.isBlank()) {
        try {
            image = credential?.optString("credentialSubject.achievement.image.id", "") ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var alt = ""
    try {
        alt = credential?.getString("issuer.name").toString()
    } catch (_: Exception) {
    }

    Log.d("PORTRAIT IMAGE", credential.toString())
    Log.d("PORTRAIT IMAGE", image)

    if (image.isNotBlank()) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(40.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(
                    width = 1.dp,
                    color = Color.Black.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                )
                .clip(RoundedCornerShape(4.dp))
        ) {
            FullSizeCredentialImage(image, alt)
        }
    }
}

