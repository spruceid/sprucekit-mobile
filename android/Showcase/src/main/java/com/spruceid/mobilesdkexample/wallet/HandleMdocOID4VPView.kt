package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.ApprovedResponse180137
import com.spruceid.mobile.sdk.rs.FieldId180137
import com.spruceid.mobile.sdk.rs.InProgressRequest180137
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.Oid4vp180137
import com.spruceid.mobile.sdk.rs.RequestMatch180137
import com.spruceid.mobile.sdk.rs.RequestedField180137
import com.spruceid.mobile.sdk.rs.Url
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MdocOID4VPState {
    Err,
    SelectCredential,
    SelectiveDisclosure,
    Loading,
    None,
}

class MdocOID4VPError {
    var title: String
    var details: String

    constructor(title: String, details: String) {
        this.title = title
        this.details = details
    }
}

@Composable
fun HandleMdocOID4VPView(
    navController: NavController,
    url: String,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel
) {
    val scope = rememberCoroutineScope()
    val credentialPacks = credentialPacksViewModel.credentialPacks

    var handler by remember { mutableStateOf<Oid4vp180137?>(null) }
    var request by remember { mutableStateOf<InProgressRequest180137?>(null) }
    var selectedMatch by remember { mutableStateOf<RequestMatch180137?>(null) }
    val uriHandler = LocalUriHandler.current

    var state by remember { mutableStateOf(MdocOID4VPState.None) }
    var error by remember { mutableStateOf<MdocOID4VPError?>(null) }

    fun onBack(url: Url? = null) {
        navController.navigate(Screen.HomeScreen.route) { popUpTo(0) }
        if (url != null) {
            uriHandler.openUri(url)
        }
    }

    when (state) {
        MdocOID4VPState.None ->
            LaunchedEffect(Unit) {
                try {
                    withContext(Dispatchers.IO) {
                        val credentials = mutableListOf<Mdoc>()
                        credentialPacks.value.forEach { credentialPack ->
                            credentialPack.list().forEach { credential ->
                                val mdoc = credential.asMsoMdoc()
                                if (mdoc != null) {
                                    credentials.add(mdoc)
                                }
                            }
                        }
                        if (credentials.isNotEmpty()) {
                            val handlerRef = Oid4vp180137(credentials, KeyManager())
                            handler = handlerRef
                            request = handlerRef.processRequest(url)
                            state = MdocOID4VPState.SelectCredential
                        } else {
                            error =
                                MdocOID4VPError(
                                    "No matching credential(s)",
                                    "There are no credentials in your wallet that match the verification request you have scanned"
                                )
                            state = MdocOID4VPState.Err
                        }
                    }
                } catch (e: Exception) {
                    error = MdocOID4VPError("No matching credential(s)", e.localizedMessage!!)
                    state = MdocOID4VPState.Err
                }
            }

        MdocOID4VPState.Err ->
            ErrorView(
                errorTitle = error!!.title,
                errorDetails = error!!.details,
                onClose = { onBack() }
            )

        MdocOID4VPState.SelectCredential ->
            MdocSelector(
                request!!.matches(),
                onContinue = { match ->
                    selectedMatch = match
                    state = MdocOID4VPState.SelectiveDisclosure
                },
                onCancel = { onBack() }
            )

        MdocOID4VPState.SelectiveDisclosure ->
            MdocFieldSelector(
                match = selectedMatch!!,
                onContinue = { approvedResponse ->
                    scope.launch {
                        try {
                            val redirect = request!!.respond(approvedResponse)
                            val credentialPack =
                                credentialPacks.value.firstOrNull { credentialPack ->
                                    credentialPack.getCredentialById(
                                        selectedMatch!!.credentialId()
                                    ) != null
                                }!!
                            val credentialInfo = getCredentialIdTitleAndIssuer(credentialPack)
                            walletActivityLogsViewModel.saveWalletActivityLog(
                                walletActivityLogs = WalletActivityLogs(
                                    credentialPackId = credentialPack.id().toString(),
                                    credentialId = credentialInfo.first,
                                    credentialTitle = credentialInfo.second,
                                    issuer = credentialInfo.third,
                                    action = "Verification",
                                    dateTime = getCurrentSqlDate(),
                                    additionalInformation = ""
                                )
                            )
                            onBack(redirect)
                        } catch (e: Exception) {
                            error = MdocOID4VPError(
                                "Failed to selective disclose fields",
                                e.localizedMessage!!
                            )
                            state = MdocOID4VPState.Err
                        }
                    }
                },
                onCancel = { onBack() }
            )

        MdocOID4VPState.Loading ->
            LoadingView(loadingText = "Loading...")
    }
}

@Composable
fun MdocFieldSelector(
    match: RequestMatch180137,
    onContinue: (ApprovedResponse180137) -> Unit,
    onCancel: () -> Unit,
    innerColumnModifier: Modifier = Modifier.fillMaxSize(),
    origin: String = "Verifier"
) {
    var selectedFields by remember {
        mutableStateOf<Set<FieldId180137>>(match.requestedFields()
            .filter { field -> field.required || !field.selectivelyDisclosable }
            .map { field -> field.id }.toHashSet()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        Text(
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = Color.Blue)) { append(origin) }
                append(" is requesting access to the following information")
            },
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = ColorStone950,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            textAlign = TextAlign.Center
        )

        Column(
            modifier = innerColumnModifier
                .verticalScroll(rememberScrollState())
                .weight(weight = 1f, fill = false)
        ) {
            match.requestedFields().forEach { field ->
                MdocFieldSelectorItem(field,
                    checked = selectedFields.contains(field.id),
                    selectField = {
                        val newSet: MutableSet<FieldId180137> = selectedFields.toMutableSet()
                        newSet.add(field.id)
                        selectedFields = newSet
                    },
                    deselectField = {
                        val newSet: MutableSet<FieldId180137> = selectedFields.toMutableSet()
                        newSet.remove(field.id)
                        selectedFields = newSet
                    })
            }

        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onCancel() },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp, color = ColorStone300, shape = RoundedCornerShape(6.dp)
                    )
                    .weight(1f)
            ) {
                Text(
                    text = "Cancel",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorStone950,
                )
            }

            Button(
                onClick = {
                    val approvedResponse = ApprovedResponse180137(
                        match.credentialId(), selectedFields.toList()
                    )
                    onContinue(approvedResponse)
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorEmerald900),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = ColorEmerald900,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .weight(1f)
            ) {
                Text(
                    text = "Approve",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorBase50,
                )
            }
        }
    }
}

@Composable
fun MdocFieldSelectorItem(
    field: RequestedField180137,
    checked: Boolean,
    selectField: () -> Unit,
    deselectField: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked,
            onCheckedChange = { checked ->
                if (checked) {
                    selectField()
                } else {
                    deselectField()
                }
            },
            enabled = field.selectivelyDisclosable,
            colors = CheckboxDefaults.colors(
                checkedColor = ColorBlue600,
                uncheckedColor = ColorStone300,
            )
        )
        Text(
            text = field.displayableName,
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = ColorStone950,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun MdocSelector(
    matches: List<RequestMatch180137>,
    onContinue: (RequestMatch180137) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedCredential by remember { mutableStateOf<RequestMatch180137?>(null) }

    fun selectCredential(credential: RequestMatch180137) {
        selectedCredential = credential
    }

    fun removeCredential() {
        selectedCredential = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        Text(
            text = "Select the credential to share",
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = ColorStone950,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            textAlign = TextAlign.Center
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .weight(weight = 1f, fill = false)
        ) {
            matches.forEach { match ->
                MdocSelectorItem(
                    match,
                    isSelected = selectedCredential?.credentialId() == match.credentialId(),
                    selectCredential = { selectCredential(match) },
                    removeCredential = { removeCredential() }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onCancel() },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp, color = ColorStone300, shape = RoundedCornerShape(6.dp)
                    )
                    .weight(1f)
            ) {
                Text(
                    text = "Cancel",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorStone950,
                )
            }

            Button(
                onClick = {
                    selectedCredential?.let { onContinue(it) }
                }, shape = RoundedCornerShape(6.dp), colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedCredential != null) {
                        ColorStone600
                    } else {
                        Color.Gray
                    }
                ), modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (selectedCredential != null) {
                            ColorStone600
                        } else {
                            Color.Gray
                        },
                        shape = RoundedCornerShape(6.dp),
                    )
                    .weight(1f)
            ) {
                Text(
                    text = "Continue",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorBase50,
                )
            }
        }
    }
}

@Composable
fun MdocSelectorItem(
    match: RequestMatch180137,
    isSelected: Boolean,
    selectCredential: () -> Unit,
    removeCredential: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val bullet = "\u2022"
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(
                width = 1.dp, color = ColorBase300, shape = RoundedCornerShape(8.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                isSelected,
                onCheckedChange = { isChecked ->
                    if (isChecked) {
                        selectCredential()
                    } else {
                        removeCredential()
                    }
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = ColorBlue600,
                    uncheckedColor = ColorStone300
                )
            )
            Text(
                text = "Mobile Drivers License",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = ColorStone950,
                modifier = Modifier.weight(1f)
            )
            if (expanded) {
                Image(painter = painterResource(id = R.drawable.collapse),
                    contentDescription = stringResource(id = R.string.collapse),
                    modifier = Modifier.clickable { expanded = false })
            } else {
                Image(painter = painterResource(id = R.drawable.expand),
                    contentDescription = stringResource(id = R.string.expand),
                    modifier = Modifier.clickable { expanded = true })
            }
        }

        if (expanded) {
            Text(
                buildAnnotatedString {
                    match.requestedFields().forEach {
                        withStyle(style = paragraphStyle) {
                            append(bullet)
                            append("\t\t")
                            append(it.displayableName)
                        }
                    }
                }, modifier = Modifier.padding(16.dp)
            )
        }
    }
}
