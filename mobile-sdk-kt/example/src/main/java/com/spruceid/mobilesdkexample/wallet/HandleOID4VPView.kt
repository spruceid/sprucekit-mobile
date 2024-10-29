package com.spruceid.mobilesdkexample.wallet

import StorageManager
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.rs.Holder
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobile.sdk.rs.PermissionRequest
import com.spruceid.mobile.sdk.rs.PermissionResponse
import com.spruceid.mobile.sdk.rs.RequestedField
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.BgSurfacePureBlue
import com.spruceid.mobilesdkexample.ui.theme.BorderSecondary
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.TextBase
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import com.spruceid.mobilesdkexample.utils.trustedDids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun HandleOID4VPView(
    navController: NavController,
    url: String
) {
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val storageManager = StorageManager(context = context)
    val credentialPacks = remember {
        mutableStateOf(CredentialPack.loadPacks(storageManager))
    }

    var credentialClaims by remember { mutableStateOf(mapOf<String, JSONObject>()) }
    var holder by remember { mutableStateOf<Holder?>(null) }
    var permissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var permissionResponse by remember { mutableStateOf<PermissionResponse?>(null) }
    var selectedCredential by remember { mutableStateOf<ParsedCredential?>(null) }

    var err by remember {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(Unit) {
        try {
            val credentials = mutableListOf<ParsedCredential>()
            credentialPacks.value
                .forEach { credentialPack ->
                    credentials.addAll(credentialPack.list())
                    credentialClaims += credentialPack.findCredentialClaims(listOf("name", "type"))
                }

            withContext(Dispatchers.IO) {
                holder = Holder.newWithCredentials(credentials, trustedDids);
                permissionRequest = holder!!.authorizationRequest(url)
            }
        } catch (e: Exception) {
            err = e.localizedMessage
        }
    }

    fun onBack() {
        navController.navigate(Screen.HomeScreen.route) {
            popUpTo(0)
        }
    }

    if (err != null) {
        ErrorView(
            errorTitle = "Error Presenting Credential",
            errorDetails = err!!,
            onClose = {
                navController.navigate(Screen.HomeScreen.route) {
                    popUpTo(0)
                }
            }
        )
    } else {
        if (permissionRequest == null) {
            LoadingView(loadingText = "Loading...")
        } else if (permissionResponse == null) {
            if (permissionRequest!!.credentials().isNotEmpty()) {
                CredentialSelector(
                    credentials = permissionRequest!!.credentials(),
                    credentialClaims = credentialClaims,
                    getRequestedFields = { credential ->
                        permissionRequest!!.requestedFields(
                            credential
                        )
                    },
                    onContinue = { selectedCredentials ->
                        scope.launch {
                            try {
                                // TODO: support multiple presentation
                                selectedCredential = selectedCredentials.first()
                                permissionResponse = permissionRequest!!.createPermissionResponse(
                                    listOf(selectedCredential!!)
                                )
                            } catch (e: Exception) {
                                err = e.localizedMessage
                            }
                        }
                    },
                    onCancel = {
                        onBack()
                    }
                )
            } else {
                ErrorView(
                    errorTitle = "No matching credential(s)",
                    errorDetails = "There are no credentials in your wallet that match the verification request you have scanned",
                    closeButtonLabel = "Cancel"
                ) { onBack() }
            }
        } else {
            DataFieldSelector(
                requestedFields = permissionRequest!!.requestedFields(selectedCredential!!),
                onContinue = {
                    scope.launch {
                        try {
                            holder!!.submitPermissionResponse(permissionResponse!!)
                            onBack()
                        } catch (e: Exception) {
                            err = e.localizedMessage
                        }
                    }
                },
                onCancel = {
                    onBack()
                }
            )
        }
    }
}

@Composable
fun DataFieldSelector(
    requestedFields: List<RequestedField>,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {

    val bullet = "\u2022"
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))
    val mockDataField = requestedFields.map { field ->
        field.name()?.replaceFirstChar(Char::titlecase) ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        Text(
            buildAnnotatedString {
                withStyle(style = SpanStyle(color = Color.Blue)) {
                    append("Verifier")
                }
                append(" is requesting access to the following information")
            },
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = TextHeader,
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
            Text(
                buildAnnotatedString {
                    mockDataField.forEach {
                        withStyle(style = paragraphStyle) {
                            append(bullet)
                            append("\t\t")
                            append(it)
                        }
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    onCancel()
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = BorderSecondary,
                        shape = RoundedCornerShape(6.dp)
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
                    onContinue()
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorEmerald900
                ),
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
                    color = TextBase,
                )
            }
        }
    }
}

@Composable
fun CredentialSelector(
    credentials: List<ParsedCredential>,
    credentialClaims: Map<String, JSONObject>,
    getRequestedFields: (ParsedCredential) -> List<RequestedField>,
    onContinue: (List<ParsedCredential>) -> Unit,
    onCancel: () -> Unit,
    allowMultiple: Boolean = false
) {
    val selectedCredentials = remember { mutableStateListOf<ParsedCredential>() }

    fun selectCredential(credential: ParsedCredential) {
        if (allowMultiple) {
            selectedCredentials.add(credential)
        } else {
            selectedCredentials.clear()
            selectedCredentials.add(credential)
        }
    }

    fun removeCredential(credential: ParsedCredential) {
        selectedCredentials.remove(credential)
    }

    fun getCredentialTitle(credential: ParsedCredential): String {
        try {
            credentialClaims[credential.id()]?.getString("name").let {
                return it.toString()
            }
        } catch (_: Exception) {
        }

        try {
            credentialClaims[credential.id()]?.getJSONArray("type").let {
                for (i in 0 until it!!.length()) {
                    if (it.get(i).toString() != "VerifiableCredential") {
                        return it.get(i).toString()
                    }
                }
                return ""
            }
        } catch (_: Exception) {
        }
        return ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp)
    ) {
        Text(
            text = "Select the credential${if (allowMultiple) "(s)" else ""} to share",
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = TextHeader,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            textAlign = TextAlign.Center
        )

        if (allowMultiple) {
            Text(
                text = "Select All",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                color = ColorBlue600,
                modifier = Modifier.clickable {
                    // TODO: implement select all
                }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .weight(weight = 1f, fill = false)
        ) {
            credentials.forEach { credential ->
                CredentialSelectorItem(
                    credential = credential,
                    requestedFields = getRequestedFields(credential),
                    getCredentialTitle = { cred -> getCredentialTitle(cred) },
                    isChecked = credential in selectedCredentials,
                    selectCredential = { cred -> selectCredential(cred) },
                    removeCredential = { cred -> removeCredential(cred) },
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
                onClick = {
                    onCancel()
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = BorderSecondary,
                        shape = RoundedCornerShape(6.dp)
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
                    if (selectedCredentials.isNotEmpty()) {
                        onContinue(selectedCredentials)
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedCredentials.isNotEmpty()) {
                        ColorStone600
                    } else {
                        Color.Gray
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (selectedCredentials.isNotEmpty()) {
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
                    color = TextBase,
                )
            }
        }
    }
}

@Composable
fun CredentialSelectorItem(
    credential: ParsedCredential,
    requestedFields: List<RequestedField>,
    getCredentialTitle: (ParsedCredential) -> String,
    isChecked: Boolean,
    selectCredential: (ParsedCredential) -> Unit,
    removeCredential: (ParsedCredential) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val bullet = "\u2022"
    val paragraphStyle = ParagraphStyle(textIndent = TextIndent(restLine = 12.sp))
    val mockDataField = requestedFields.map { field ->
        field.name()?.replaceFirstChar(Char::titlecase) ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(
                width = 1.dp,
                color = ColorBase300,
                shape = RoundedCornerShape(8.dp)
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
                checked = isChecked,
                onCheckedChange = { isChecked ->
                    if (isChecked) {
                        selectCredential(credential)
                    } else {
                        removeCredential(credential)
                    }
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = BgSurfacePureBlue,
                    uncheckedColor = BorderSecondary
                )
            )
            Text(
                text = getCredentialTitle(credential),
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = ColorStone950,
                modifier = Modifier.weight(1f)
            )
            if (expanded) {
                Image(
                    painter = painterResource(id = R.drawable.collapse),
                    contentDescription = stringResource(id = R.string.collapse),
                    modifier = Modifier.clickable { expanded = false }
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.expand),
                    contentDescription = stringResource(id = R.string.expand),
                    modifier = Modifier.clickable { expanded = true }
                )
            }

        }

        if (expanded) {
            Text(
                buildAnnotatedString {
                    mockDataField.forEach {
                        withStyle(style = paragraphStyle) {
                            append(bullet)
                            append("\t\t")
                            append(it)
                        }
                    }
                },
                modifier = Modifier.padding(16.dp)
            )
        }
    }

}