package com.spruceid.mobilesdkexample.credentials

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
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
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialsViewModel
import com.spruceid.mobile.sdk.PresentmentState
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.getPermissions
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.rememberQrBitmapPainter
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald800
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.RenderCredentialFieldValue
import com.spruceid.mobilesdkexample.utils.checkAndRequestBluetoothPermissions
import com.spruceid.mobilesdkexample.utils.formatCredentialFieldValue
import com.spruceid.mobilesdkexample.utils.getCredentialFieldType
import com.spruceid.mobilesdkexample.utils.getFieldDisplayName
import com.spruceid.mobilesdkexample.utils.getFieldSortOrder

@Composable
fun ShareMdocView(
    credentialViewModel: CredentialsViewModel,
    credentialPack: CredentialPack? = null,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    val qrCodeUri by credentialViewModel.qrCodeUri.collectAsState()
    val currentState by credentialViewModel.currState.collectAsState()
    val credentials by credentialViewModel.credentials.collectAsState()
    val error by credentialViewModel.error.collectAsState()
    val bluetoothPermissionsGranted by credentialViewModel.bluetoothPermissionsGranted.collectAsState()

    var isBluetoothEnabled by remember {
        mutableStateOf(getBluetoothManager(context)!!.adapter.isEnabled)
    }
    val launcherMultiplePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        if (permissionsMap.isNotEmpty()) {
            val areGranted = permissionsMap.values.all { it }
            credentialViewModel.setBluetoothPermissionsGranted(areGranted);

            if (!areGranted) {
                // @TODO: Show dialog
            }
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

    DisposableEffect(Unit) {
        onDispose {
            credentialViewModel.setBluetoothPermissionsGranted(false)
            // Clean up BLE connection when leaving the screen
            credentialViewModel.cancel()
        }
    }

    LaunchedEffect(Unit) {
        checkAndRequestBluetoothPermissions(
            context,
            getPermissions().toTypedArray(),
            launcherMultiplePermissions,
            credentialViewModel
        )
    }
    LaunchedEffect(key1 = bluetoothPermissionsGranted) {
        if (isBluetoothEnabled && bluetoothPermissionsGranted) {
            // We do check for permissions
            @SuppressLint("MissingPermission")
            credentialPack?.let { pack ->
                pack.list().firstOrNull()?.asMsoMdoc()?.let { mdoc ->
                    credentialViewModel.present(getBluetoothManager(context)!!, mdoc)
                }
            }
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
            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = ColorStone950,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Loading...",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        color = ColorStone950
                    )
                }
            }
            ShareMdocSelectiveDisclosureView(
                credentialViewModel = credentialViewModel,
                credentialPack = credentialPack,
                onCancel = onCancel
            )
        }

        PresentmentState.SUCCESS ->
            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = ColorEmerald800
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Successfully presented credential.",
                        fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = ColorEmerald800,
                        textAlign = TextAlign.Center
                    )
                }
            }

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
    credentialPack: CredentialPack? = null,
    onCancel: () -> Unit
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
        containerColor = ColorBase1,
        shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height((LocalConfiguration.current.screenHeightDp * .8f).dp)
                .padding(horizontal = 24.dp)
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
                itemsRequests.map { itemsRequest ->
                    Column {
                        itemsRequest.namespaces.entries
                            .sortedBy { if (it.key == "org.iso.18013.5.1") 0 else 1 }
                            .forEach { namespaceSpec ->
                                Column {
                                    Text(
                                        text = namespaceSpec.key,
                                        fontFamily = Inter,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 18.sp,
                                        color = ColorStone950,
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                    namespaceSpec.value.entries
                                        .sortedBy { getFieldSortOrder(it.key) }
                                        .forEach { namespace ->
                                            ShareMdocSelectiveDisclosureNamespaceItem(
                                                namespace = namespace,
                                                credentialPack = credentialPack,
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
            }

            // Separator line above buttons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .height(1.dp)
                    .background(ColorStone200)
            )


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onCancel() },
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = ColorStone950,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp, color = ColorStone300, shape = RoundedCornerShape(20.dp)
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
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ColorEmerald900),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = ColorEmerald900,
                            shape = RoundedCornerShape(20.dp),
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
    credentialPack: CredentialPack? = null,
    isChecked: Boolean,
    onCheck: (Boolean) -> Unit
) {

    // Get the display name
    val displayName = getFieldDisplayName(namespace.key)

    // Get the field value from the credential pack
    val rawFieldValue = credentialPack?.let { pack ->
        try {
            val claims = pack.findCredentialClaims(listOf(namespace.key))
            claims.values.firstOrNull()?.optString(namespace.key) ?: ""
        } catch (e: Exception) {
            ""
        }
    } ?: ""

    // Get field type based on display name AND field value
    val fieldType = getCredentialFieldType(displayName, rawFieldValue)

    // Format the field value based on its type
    val formattedValue = if (rawFieldValue.isNotEmpty()) {
        formatCredentialFieldValue(rawFieldValue, fieldType, namespace.key)
    } else {
        ""
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
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
            text = displayName,
            fontFamily = Inter,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = ColorStone950,
            modifier = Modifier.weight(1f)
        )

        // Render field value 
        RenderCredentialFieldValue(
            fieldType = fieldType,
            rawFieldValue = rawFieldValue,
            formattedValue = formattedValue,
            displayName = displayName
        )
    }
}
