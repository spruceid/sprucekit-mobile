package com.spruceid.mobilesdkexample.credentials

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialsViewModel
import com.spruceid.mobile.sdk.PresentmentState
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.getPermissions
import com.spruceid.mobilesdkexample.rememberQrBitmapPainter
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme
import com.spruceid.mobilesdkexample.utils.checkAndRequestBluetoothPermissions

@Composable
fun ShareMdocView(
    credentialViewModel: CredentialsViewModel,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    val session by credentialViewModel.session.collectAsState()
    val currentState by credentialViewModel.currState.collectAsState()
    val credentials by credentialViewModel.credentials.collectAsState()
    val error by credentialViewModel.error.collectAsState()


    var isBluetoothEnabled by remember {
        mutableStateOf(getBluetoothManager(context)!!.adapter.isEnabled)
    }

    val launcherMultiplePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val areGranted = permissionsMap.values.reduce { acc, next -> acc && next }
        if (!areGranted) {
            // @TODO: Show dialog
        }
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

    LaunchedEffect(key1 = isBluetoothEnabled) {
        checkAndRequestBluetoothPermissions(
            context,
            getPermissions().toTypedArray(),
            launcherMultiplePermissions
        )
        if (isBluetoothEnabled) {
            credentialViewModel.present(getBluetoothManager(context)!!)
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
                }
            }

        PresentmentState.ENGAGING_QR_CODE -> {
            if (session!!.getQrCodeUri().isNotEmpty()) {
                Image(
                    painter = rememberQrBitmapPainter(
                        session!!.getQrCodeUri(),
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
                onCancel = onCancel
            )
        }

        PresentmentState.SUCCESS -> Text(
            text = "Successfully presented credential.",
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            modifier = Modifier.padding(vertical = 20.dp)
        )

        PresentmentState.ERROR -> Text(
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
    onCancel: () -> Unit
) {
    val itemsRequests by credentialViewModel.itemsRequest.collectAsState()
    val allowedNamespaces by credentialViewModel.allowedNamespaces.collectAsState()

    val selectNamespacesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            onCancel()
        },
        modifier = Modifier
            .fillMaxHeight(0.8f),
        sheetState = selectNamespacesSheetState,
        dragHandle = null,
        containerColor = ColorBase1,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            Modifier
                .padding(all = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            itemsRequests.map { itemsRequest ->
                Column {
                    Text(
                        text = "Document being requested:\n\t\t${itemsRequest.docType}\n",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    itemsRequest.namespaces.map { namespaceSpec ->
                        Column {
                            Text(
                                text = "The following fields are being requested by the reader:\n",
                                fontFamily = Inter,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = "\t\t${namespaceSpec.key}",
                                fontFamily = Inter,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            namespaceSpec.value.forEach { namespace ->
                                ShareMdocSelectiveDisclosureNamespaceItem(
                                    namespace = namespace,
                                    isChecked = allowedNamespaces[itemsRequest.docType]?.get(
                                        namespaceSpec.key
                                    )?.contains(namespace.key) ?: false,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    modifier = Modifier
                        .padding(end = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        contentColor = Color.White,
                    ),
                    onClick = {
                        onCancel()
                    }
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                    )
                }
                Button(onClick = {
                    try {
                        credentialViewModel.submitNamespaces(allowedNamespaces)
                    } catch (e: Error) {
                        Log.e("SelectiveDisclosureView", e.stackTraceToString())
                    }
                }) {
                    Text(
                        text = "Share fields",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
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
    MobileSdkTheme {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = namespace.key,
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
            )
            Checkbox(
                isChecked,
                onCheckedChange = onCheck
            )
        }
    }
}