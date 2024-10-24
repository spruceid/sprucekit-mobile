package com.spruceid.mobilesdkexample.wallet

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.checkAndRequestBluetoothPermissions

@Composable
fun ShareView(
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
            SelectiveDisclosureView(
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