package com.spruceid.mobilesdkexample.credentials

import android.R
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import com.spruceid.mobile.sdk.CredentialPresentData
import com.spruceid.mobile.sdk.CredentialsViewModel
import com.spruceid.mobile.sdk.PresentmentState
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.getPermissions
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.nfc.NfcListenManager
import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo
import com.spruceid.mobilesdkexample.rememberQrBitmapPainter
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import kotlinx.coroutines.*

@Composable
fun QrShareMdocView(
    credentialViewModel: CredentialsViewModel,
    mdoc: Mdoc,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val qrCodeUri by credentialViewModel.qrCodeUri.collectAsState()
    val currentState by credentialViewModel.currState.collectAsState()
    val credentials by credentialViewModel.credentials.collectAsState()
    val error by credentialViewModel.error.collectAsState()
    val bluetoothPermissionsGranted by credentialViewModel.bluetoothPermissionsGranted.collectAsState()
    var forceRefresh by remember { mutableStateOf(true) }

    var isBluetoothEnabled by remember {
        mutableStateOf(getBluetoothManager(context)!!.adapter.isEnabled)
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state =
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> isBluetoothEnabled = false
                        BluetoothAdapter.STATE_ON -> isBluetoothEnabled = true
                        else -> {}
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    when (currentState) {
        PresentmentState.UNINITIALIZED -> {

            LaunchedEffect(key1 = bluetoothPermissionsGranted, forceRefresh) {
                if (isBluetoothEnabled && bluetoothPermissionsGranted) {
                    // We do check for permissions
                    @SuppressLint("MissingPermission")
                    credentialViewModel.present(
                        getBluetoothManager(context)!!,
                        mdoc,
                        CredentialPresentData.Qr(),
                    )
                }
            }
            if (credentials.isNotEmpty()) {
                if (!isBluetoothEnabled) {
                    Text(
                        text = "Enable Bluetooth to initialize",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
            }
        }

        PresentmentState.TIMEOUT -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 48.dp),
            ) {
                Button(
                    onClick = {
                        onCancel()
                        forceRefresh = !forceRefresh
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    border = BorderStroke(width = 1.dp, color = Color.Black)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh the QR Code",
                        modifier = Modifier
                            .size(24.dp),
                        tint = Color.Black,
                    )
                    Text(
                        text = "Refresh QR Code",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        color = Color.Black,
                    )
                }
            }
        }

        PresentmentState.ENGAGING_QR_CODE -> {
            if (qrCodeUri.isNotEmpty()) {
                Image(
                    painter = rememberQrBitmapPainter(
                        qrCodeUri,
                        300.dp,
                    ),
                    contentDescription = "Share QRCode",
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        PresentmentState.SELECT_NAMESPACES -> {
            Text(
                text = "Selecting namespaces...",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 20.dp)
            )
            ShareMdocSelectiveDisclosureView(
                credentialViewModel = credentialViewModel,
                mdoc = mdoc,
                onCancel = onCancel
            )
        }
        PresentmentState.SUCCESS ->
            Text(
                text = "Successfully presented credential.",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        PresentmentState.ERROR ->
            Text(
                text = "Error: $error",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 20.dp)
            )
    }
}

@Composable
fun NfcShareMdocView(
    credentialViewModel: CredentialsViewModel,
    mdoc: Mdoc,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    val currentState by credentialViewModel.currState.collectAsState()
    val credentials by credentialViewModel.credentials.collectAsState()
    val error by credentialViewModel.error.collectAsState()
    val bluetoothPermissionsGranted by credentialViewModel.bluetoothPermissionsGranted.collectAsState()

    var isBluetoothEnabled by remember {
        mutableStateOf(getBluetoothManager(context)!!.adapter.isEnabled)
    }

    DisposableEffect(Unit) {

        NfcListenManager.userRequested = true
        NfcPresentationService.shareScreenCallback = { carrierInfo: NegotiatedCarrierInfo ->
            if(isBluetoothEnabled && bluetoothPermissionsGranted) {
                credentialViewModel.viewModelScope.launch {
                    @SuppressLint("MissingPermission")
                    credentialViewModel.present(
                        getBluetoothManager(context)!!,
                        mdoc,
                        CredentialPresentData.Nfc(carrierInfo)
                    )
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state =
                        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> isBluetoothEnabled = false
                        BluetoothAdapter.STATE_ON -> isBluetoothEnabled = true
                        else -> {}
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            NfcPresentationService.shareScreenCallback = null
            context.unregisterReceiver(receiver)
            NfcListenManager.userRequested = false
        }
    }

    when (currentState) {
        PresentmentState.UNINITIALIZED ->
            if (credentials.isNotEmpty()) {
                if (!isBluetoothEnabled) {
                    Text(
                        text = "Enable Bluetooth to initialize",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                } else {
                    Text(
                        text = "Please tap the device against a reader.",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
            }
        PresentmentState.ENGAGING_QR_CODE -> {
            // Unreachable
        }
        PresentmentState.TIMEOUT -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 48.dp),
            ) {
                Text(
                    text = "Failed to establish link to reader.\nPlease try again.",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                )
            }
        }
        PresentmentState.SELECT_NAMESPACES -> {
            Text(
                text = "Selecting namespaces...",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 20.dp)
            )
            ShareMdocSelectiveDisclosureView(
                credentialViewModel = credentialViewModel,
                mdoc = mdoc,
                onCancel = onCancel
            )
        }
        PresentmentState.SUCCESS ->
            Text(
                text = "Successfully presented credential.",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        PresentmentState.ERROR ->
            Text(
                text = "Error: $error",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 20.dp)
            )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareMdocSelectiveDisclosureView(
    credentialViewModel: CredentialsViewModel,
    mdoc: Mdoc,
    onCancel: () -> Unit,
) {
    val itemsRequests by credentialViewModel.itemsRequest.collectAsState()
    val allowedNamespaces by credentialViewModel.allowedNamespaces.collectAsState()

    val selectNamespacesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        itemsRequests.map { itemsRequest ->
            credentialViewModel.addAllAllowedNamespaces(
                itemsRequest.docType,
                itemsRequest.namespaces
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            onCancel()
        },
        sheetState = selectNamespacesSheetState,
        dragHandle = null,
        containerColor = ColorBase1,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height((LocalConfiguration.current.screenHeightDp * .8f).dp)
                .padding(horizontal = 24.dp)
                .padding(top = 48.dp)
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(style = SpanStyle(color = Color.Blue)) { append("Verifier") }
                    append(" is requesting access to the following information")
                },
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = ColorStone950,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                textAlign = TextAlign.Center
            )

            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .weight(weight = 1f, fill = false)
            ) {
                itemsRequests.map { itemsRequest ->
                    Column {
                        itemsRequest.namespaces.map { namespaceSpec ->
                            Column {
                                Text(
                                    text = namespaceSpec.key,
                                    fontFamily = Inter,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    color = ColorStone950,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                                namespaceSpec.value.forEach { namespace ->
                                    ShareMdocSelectiveDisclosureNamespaceItem(
                                        namespace = namespace,
                                        isChecked = allowedNamespaces[itemsRequest.docType]
                                            ?.get(namespaceSpec.key)
                                            ?.contains(namespace.key)
                                            ?: false,
                                        onCheck = { _ ->
                                            credentialViewModel.toggleAllowedNamespace(
                                                itemsRequest.docType,
                                                namespaceSpec.key,
                                                namespace.key
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
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
                            width = 1.dp,
                            color = ColorStone300,
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
                        try {
                            credentialViewModel.submitNamespaces(allowedNamespaces)
                        } catch (e: Error) {
                            Log.e("SelectiveDisclosureView", e.stackTraceToString())
                        }
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
}

@Composable
fun ShareMdocSelectiveDisclosureNamespaceItem(
    namespace: Map.Entry<String, Boolean>,
    isChecked: Boolean,
    onCheck: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        Checkbox(
            isChecked,
            onCheckedChange = onCheck,
            enabled = true,
            colors = CheckboxDefaults.colors(
                checkedColor = ColorBlue600,
                uncheckedColor = ColorStone300,
            )
        )
        Text(
            text = namespace.key,
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = ColorStone950,
            modifier = Modifier.weight(1f)
        )
    }
}
