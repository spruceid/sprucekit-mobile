package com.spruceid.coloradofwd.credentials.genericCredentialItem

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobile.sdk.jsonEncodedDetailsAll
import com.spruceid.mobile.sdk.ui.BaseCard
import com.spruceid.mobile.sdk.ui.CardRenderingListView
import com.spruceid.mobile.sdk.ui.toCardRendering
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.CredentialImage
import com.spruceid.mobilesdkexample.credentials.CredentialOptionsDialogActions
import com.spruceid.mobilesdkexample.credentials.CredentialStatusSmall
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.credentialTypeDisplayName
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
            .padding(vertical = 10.dp)
            .border(
                width = 1.dp,
                color = ColorBase300,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
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
    val credential = values.toList().firstNotNullOfOrNull {
        val cred = credentialPack.getCredentialById(it.first)
        val mdoc = cred?.asMsoMdoc()
        val dcSdJwt = cred?.asDcSdJwt()
        try {
            if (
                cred?.asJwtVc() != null ||
                cred?.asJsonVc() != null ||
                cred?.asSdJwt() != null
            ) {
                it.second
            } else if (mdoc != null) {
                val details = mdoc.jsonEncodedDetailsAll()
                val issuer = details.opt("issuing_authority")
                if (issuer != null && issuer.toString().isNotBlank()) {
                    it.second.put("issuer", issuer)
                }
                it.second
            } else if (dcSdJwt != null) {
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

    if (description.isBlank()) {
        try {
            description = credential?.getString("issuing_authority").toString()
        } catch (_: Exception) {
        }
    }


    Column {
        Text(
            text = description,
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = ColorStone600
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
                cred?.asSdJwt() != null ||
                cred?.asDcSdJwt() != null
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
        titleKeys = listOf("name", "type"),
        titleFormatter = { values ->
            val credential = values.toList().firstNotNullOfOrNull {
                val cred = credentialPack.getCredentialById(it.first)
                try {
                    val mdoc = cred?.asMsoMdoc()
                    val dcSdJwt = cred?.asDcSdJwt()
                    if (
                        cred?.asJwtVc() != null ||
                        cred?.asJsonVc() != null ||
                        cred?.asSdJwt() != null
                    ) {
                        it.second
                    } else if (mdoc != null) {
                        it.second.put("name", credentialTypeDisplayName(mdoc.doctype()))
                        it.second
                    } else if (dcSdJwt != null) {
                        it.second.put("name", credentialTypeDisplayName(dcSdJwt.vct()))
                        it.second
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }
            var title = ""
            try {
                title = credential?.get("name").toString()
                if (title.isBlank()) {
                    val arrayTypes = credential?.getJSONArray("type")
                    if (arrayTypes != null) {
                        for (i in 0 until arrayTypes.length()) {
                            if (arrayTypes.get(i).toString() != "VerifiableCredential") {
                                title = arrayTypes.get(i).toString().splitCamelCase()
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {
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
                                    showBottomSheet = true
                                }
                        )
                    }
                }
                Text(
                    text = title,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Medium,
                    fontSize = 20.sp,
                    color = ColorStone950,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (showBottomSheet) {
                    CredentialOptionsDialogActions(
                        setShowBottomSheet = { show ->
                            showBottomSheet = show
                        },
                        onDelete = onDelete,
                        onExport = {
                            onExport?.let { it(title) }
                        }
                    )
                }
            }
        },
        descriptionKeys = listOf("description", "issuer", "issuing_authority"),
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

        }
    )

    BaseCard(
        credentialPack = credentialPack,
        rendering = listRendering.toCardRendering()
    )
}