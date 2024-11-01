package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.ui.BaseCard
import com.spruceid.mobile.sdk.ui.CardRenderingDetailsField
import com.spruceid.mobile.sdk.ui.CardRenderingDetailsView
import com.spruceid.mobile.sdk.ui.CardRenderingListView
import com.spruceid.mobile.sdk.ui.toCardRendering
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.Bg
import com.spruceid.mobilesdkexample.ui.theme.CredentialBorder
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.SecondaryButtonRed
import com.spruceid.mobilesdkexample.ui.theme.SpruceBlue
import com.spruceid.mobilesdkexample.ui.theme.TextBody
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import com.spruceid.mobilesdkexample.utils.addCredential
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import kotlinx.coroutines.launch
import org.json.JSONObject

class GenericCredentialItem : ICredentialView {
    override var credentialPack: CredentialPack
    private val onDelete: (() -> Unit)?

    constructor(credentialPack: CredentialPack, onDelete: (() -> Unit)? = null) {
        this.credentialPack = credentialPack
        this.onDelete = onDelete
    }

    constructor(rawCredential: String, onDelete: (() -> Unit)? = null) {
        this.credentialPack = addCredential(CredentialPack(), rawCredential)
        this.onDelete = onDelete
    }

    @Composable
    private fun descriptionFormatter(values: Map<String, JSONObject>) {
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

        Column {
            Text(
                text = description,
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = TextBody
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    @Composable
    private fun leadingIconFormatter(values: Map<String, JSONObject>) {
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
            image = credential?.getString("issuer.image") ?: ""
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
    fun listItem() {
        val listRendering = CardRenderingListView(
            titleKeys = listOf("name", "type"),
            titleFormatter = { values ->
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
                Text(
                    text = title,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Medium,
                    fontSize = 20.sp,
                    color = TextHeader,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            },
            descriptionKeys = listOf("description", "issuer"),
            descriptionFormatter = { values ->
                descriptionFormatter(values)
            },
            leadingIconKeys = listOf("issuer.image", "issuer.name", "type"),
            leadingIconFormatter = { values ->
                leadingIconFormatter(values)
            }
        )

        BaseCard(
            credentialPack = credentialPack,
            rendering = listRendering.toCardRendering()
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun listItemWithOptions() {
        val sheetState = rememberModalBottomSheetState()
        val scope = rememberCoroutineScope()
        var showBottomSheet by remember { mutableStateOf(false) }

        val listRendering = CardRenderingListView(
            titleKeys = listOf("name", "type"),
            titleFormatter = { values ->
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
                    Text(
                        text = title,
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        color = TextHeader,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            },
            descriptionKeys = listOf("description", "issuer"),
            descriptionFormatter = { values ->
                descriptionFormatter(values)
            },
            leadingIconKeys = listOf("issuer.image", "issuer.name", "type"),
            leadingIconFormatter = { values ->
                leadingIconFormatter(values)
            }
        )

        BaseCard(
            credentialPack = credentialPack,
            rendering = listRendering.toCardRendering()
        )

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showBottomSheet = false
                },
                sheetState = sheetState,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Text(
                    text = "Credential Options",
                    textAlign = TextAlign.Center,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = TextHeader,
                    modifier = Modifier
                        .fillMaxWidth()
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Button(
                    onClick = {
                        showBottomSheet = false
                        onDelete?.invoke()
                    },
                    shape = RoundedCornerShape(5.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = SecondaryButtonRed,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Delete",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        color = SecondaryButtonRed,
                    )
                }

                Button(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showBottomSheet = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(5.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = SpruceBlue,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        color = SpruceBlue,
                    )
                }
            }
        }
    }

    @Composable
    override fun credentialListItem(withOptions: Boolean) {
        Column(
            Modifier
                .padding(vertical = 10.dp)
                .border(
                    width = 1.dp,
                    color = CredentialBorder,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            if (withOptions) {
                listItemWithOptions()
            } else {
                listItem()
            }
        }
    }

    @Composable
    override fun credentialListItem() {
        Column(
            Modifier
                .padding(vertical = 10.dp)
                .border(
                    width = 1.dp,
                    color = CredentialBorder,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
        ) {
            listItem()
        }
    }

    @Composable
    override fun credentialDetails() {
        val detailsRendering = CardRenderingDetailsView(
            fields = listOf(
                CardRenderingDetailsField(
                    // it's also possible just request the credentialSubject and cast it to JSONObject
                    keys = listOf(),
                    formatter = { values ->
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
                    genericObjectDisplayer(
                            credential!!,
                            listOf("id", "identifier", "type", "proof", "renderMethod", "@context")
                        )
                    }
                )
            )
        )

        Box(Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
        ) {
            BaseCard(
                credentialPack = credentialPack,
                rendering = detailsRendering.toCardRendering()
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun credentialPreviewAndDetails() {
        var sheetOpen by remember {
            mutableStateOf(false)
        }

        Box(
            Modifier
                .clickable {
                    sheetOpen = true
                }
        ) {
            credentialListItem(withOptions = true)
        }

        if (sheetOpen) {
            ModalBottomSheet(
                onDismissRequest = {
                    sheetOpen = false
                },
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .nestedScroll(rememberNestedScrollInteropConnection()),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Bg,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Review Info",
                        textAlign = TextAlign.Center,
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = TextHeader,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                    // Header
                    credentialListItem()

                    // Body
                    credentialDetails()
                }
            }
        }
    }
}
