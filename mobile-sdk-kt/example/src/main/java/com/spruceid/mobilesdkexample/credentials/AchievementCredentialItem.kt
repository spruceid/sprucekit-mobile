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
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.addCredential
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AchievementCredentialItem : ICredentialView {
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
                color = ColorStone600
            )
            Spacer(modifier = Modifier.height(16.dp))
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
                    color = ColorStone950,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            },
            descriptionKeys = listOf("description", "issuer"),
            descriptionFormatter = { values ->
                descriptionFormatter(values)
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
                        color = ColorStone950,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            },
            descriptionKeys = listOf("description", "issuer"),
            descriptionFormatter = { values ->
                descriptionFormatter(values)
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
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Text(
                    text = "Credential Options",
                    textAlign = TextAlign.Center,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = ColorStone950,
                    modifier = Modifier
                        .fillMaxWidth()
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Button(
                    onClick = {
                        onDelete?.invoke()
                    },
                    shape = RoundedCornerShape(5.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = ColorRose600,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Delete",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        color = ColorRose600,
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
                        contentColor = ColorBlue600,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        color = ColorBlue600,
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
                    color = ColorBase300,
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
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
                    color = ColorBase300,
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
                    keys = listOf("awardedDate", "credentialSubject.identity"),
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

                        val awardedDate = credential?.get("awardedDate")?.toString()
                        val ISO8601DateFormat =
                            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]Z")
                        val parsedDate = OffsetDateTime.parse(awardedDate, ISO8601DateFormat)
                        val localZoneParsedDate =
                            parsedDate.atZoneSameInstant(ZoneId.systemDefault())
                        val dateTimeFormatter =
                            DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' h:mm a")

                        val identity = credential?.getJSONArray("credentialSubject.identity")
                        val details = identity?.let {
                            MutableList(it.length()) { i ->
                                val obj = identity.get(i) as JSONObject
                                Pair(obj["identityType"].toString(), obj["identityHash"].toString())
                            }
                        }
                        details?.add(
                            0,
                            Pair("awardedDate", localZoneParsedDate.format(dateTimeFormatter))
                        )

                        Row(
                            Modifier.padding(horizontal = 12.dp)
                        ) {
                            Column {
                                details?.map { detail ->
                                    Text(
                                        text = detail.first.splitCamelCase(),
                                        fontFamily = Inter,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 14.sp,
                                        color = ColorStone600,
                                        modifier = Modifier.padding(top = 10.dp)
                                    )
                                    Text(
                                        text = detail.second,
                                        fontFamily = Inter,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1.0f))
                        }
                    }
                )
            )
        )

        Box(Modifier.fillMaxWidth()) {
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
                containerColor = ColorBase1,
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Review Info",
                        textAlign = TextAlign.Center,
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = ColorStone950,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
                    credentialListItem()

                    credentialDetails()
                }
            }
        }
    }
}
