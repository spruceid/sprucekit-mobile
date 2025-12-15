package com.spruceid.mobilesdkexample.verifier

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.IsoMdlReader
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.getPermissions
import com.spruceid.mobile.sdk.ui.GenericCameraXScanner
import com.spruceid.mobile.sdk.ui.QrCodeAnalyzer
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue100
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue200
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue300
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue400
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue800
import com.spruceid.mobilesdkexample.ui.theme.ColorRose50
import com.spruceid.mobilesdkexample.ui.theme.ColorRose200
import com.spruceid.mobilesdkexample.ui.theme.ColorRose300
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.checkAndRequestBluetoothPermissions
import com.spruceid.mobilesdkexample.viewmodels.TrustedCertificatesViewModel
import kotlinx.coroutines.launch

val kioskElements: Map<String, Map<String, Boolean>> = mapOf(
    "org.iso.18013.5.1" to mapOf(
        "family_name" to false,
        "given_name" to false,
        "birth_date" to false,
        "issue_date" to false,
        "expiry_date" to false,
    )
)

enum class KioskState {
    SCANNING,
    CONNECTING,
    SUCCESS,
    ERROR,
    BLUETOOTH_REQUIRED
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VerifyKioskView(navController: NavController) {
    val trustedCertificatesViewModel: TrustedCertificatesViewModel = activityHiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reader: IsoMdlReader? = null

    var kioskState by remember { mutableStateOf(KioskState.SCANNING) }
    var scannedUri by remember { mutableStateOf<String?>(null) }
    var isBluetoothEnabled by remember {
        mutableStateOf(getBluetoothManager(context)?.adapter?.isEnabled ?: false)
    }

    val launcherMultiplePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        permissionsMap.values.reduce { acc, next -> acc && next }
    }

    // Bluetooth state listener
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> isBluetoothEnabled = false
                        BluetoothAdapter.STATE_ON -> isBluetoothEnabled = true
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val bleCallback: BLESessionStateDelegate = object : BLESessionStateDelegate() {
        override fun update(state: Map<String, Any>) {
            if (state.containsKey("mdl")) {
                reader?.handleMdlReaderResponseData(state["mdl"] as ByteArray)
                kioskState = KioskState.SUCCESS
            }
        }

        override fun error(error: Exception) {
            kioskState = KioskState.ERROR
        }
    }

    // Auto-retry when Bluetooth enabled
    LaunchedEffect(isBluetoothEnabled) {
        if (isBluetoothEnabled && kioskState == KioskState.BLUETOOTH_REQUIRED && scannedUri != null) {
            val bluetooth = getBluetoothManager(context.applicationContext)
            kioskState = KioskState.CONNECTING
            try {
                reader = IsoMdlReader(
                    bleCallback,
                    scannedUri!!,
                    kioskElements,
                    trustedCertificatesViewModel.trustedCertificates.value.map { it.content },
                    bluetooth!!,
                    context.applicationContext
                )
            } catch (e: Exception) {
                e.localizedMessage?.let { Toast.showError(it) }
                kioskState = KioskState.ERROR
            }
        }
    }

    fun back() {
        navController.navigate(Screen.HomeScreen.route.replace("{tab}", "verifier")) {
            popUpTo(0)
        }
    }

    fun startOver() {
        scannedUri = null
        reader = null
        kioskState = KioskState.SCANNING
    }

    fun onRead(content: String) {
        scannedUri = content

        checkAndRequestBluetoothPermissions(
            context.applicationContext,
            getPermissions().toTypedArray(),
            launcherMultiplePermissions
        )

        val bluetooth = getBluetoothManager(context.applicationContext)

        if (bluetooth?.adapter?.isEnabled != true) {
            kioskState = KioskState.BLUETOOTH_REQUIRED
            return
        }

        kioskState = KioskState.CONNECTING

        scope.launch {
            try {
                reader = IsoMdlReader(
                    bleCallback,
                    content,
                    kioskElements,
                    trustedCertificatesViewModel.trustedCertificates.value.map { it.content },
                    bluetooth,
                    context.applicationContext
                )
            } catch (e: Exception) {
                e.localizedMessage?.let { Toast.showError(it) }
                kioskState = KioskState.ERROR
            }
        }
    }

    when (kioskState) {
        KioskState.SCANNING -> {
            KioskScanView(onCancel = ::back, onRead = ::onRead)
        }
        KioskState.CONNECTING -> {
            LoadingView(
                loadingText = "Connecting...",
                cancelButtonLabel = "Cancel",
                onCancel = ::back
            )
        }
        KioskState.SUCCESS -> {
            KioskSuccessView(onStartOver = ::startOver)
        }
        KioskState.ERROR -> {
            KioskFailureView(onStartOver = ::startOver)
        }
        KioskState.BLUETOOTH_REQUIRED -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Turn on Bluetooth to continue.")
            }
        }
    }
}

@Composable
fun KioskScanView(
    onCancel: () -> Unit,
    onRead: (String) -> Unit
) {
    val scannerWidthMultiplier = 0.6f
    val cornerRadiusDp = 12.dp

    var scannerBottomPx by remember { mutableFloatStateOf(0f) }
    var badgeHeight by remember { mutableFloatStateOf(0f) }

    GenericCameraXScanner(
        title = "",
        subtitle = "",
        onCancel = onCancel,
        hideCancelButton = true,
        fontFamily = Inter,
        imageAnalyzer = QrCodeAnalyzer(
            onQrCodeScanned = { result -> onRead(result) }
        ),
        background = {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ColorBase1)
                        .drawWithContent {
                            val canvasWidth = size.width
                            val canvasHeight = size.height
                            val scannerWidth = canvasWidth * scannerWidthMultiplier
                            val left = (canvasWidth - scannerWidth) / 2
                            val top = canvasHeight * 0.35f
                            val cornerRadius = cornerRadiusDp.toPx()

                            scannerBottomPx = top + scannerWidth

                            drawContent()

                            // Camera cut
                            drawRoundRect(
                                topLeft = Offset(left, top),
                                size = Size(scannerWidth, scannerWidth),
                                color = Color.Transparent,
                                blendMode = BlendMode.SrcIn,
                                cornerRadius = CornerRadius(cornerRadius),
                            )

                            // Border
                            drawRoundRect(
                                topLeft = Offset(left, top),
                                size = Size(scannerWidth, scannerWidth),
                                color = ColorBlue600,
                                style = Stroke(4.dp.toPx()),
                                cornerRadius = CornerRadius(cornerRadius),
                            )
                        }
                )

                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 30.dp)
                        .padding(top = 20.dp)
                ) {
                    KioskHeader()
                }

                // Title
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 170.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan QR Code",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        color = ColorBlue600
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Present your digital ID QR code",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        color = ColorStone600
                    )
                }

                // Scanning badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(0, (scannerBottomPx - (badgeHeight / 2) - 10.dp.toPx()).toInt())
                        }
                        .background(ColorBlue600, RoundedCornerShape(100.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onGloballyPositioned { coordinates ->
                            badgeHeight = coordinates.size.height.toFloat()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Detecting...",
                            fontFamily = Inter,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }
                }

                // Cancel button
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(horizontal = 30.dp)
                        .padding(bottom = 30.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = onCancel,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.Black,
                        ),
                        border = BorderStroke(1.dp, ColorStone300),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Cancel",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black,
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun KioskHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.spruce_logo),
            contentDescription = "Spruce Logo",
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Spruce County",
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = ColorStone950
        )
    }
}

@Composable
fun KioskSuccessView(onStartOver: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBase1)
            .clickable { onStartOver() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            KioskHeader()

            Spacer(modifier = Modifier.weight(1f))

            // Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .shadow(4.dp, CircleShape)
                    .background(
                        brush = Brush.verticalGradient(listOf(ColorBase50, ColorBlue200)),
                        shape = CircleShape
                    )
                    .border(2.dp, ColorBlue300, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Success",
                    modifier = Modifier.size(40.dp),
                    tint = ColorBlue600
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome!",
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                color = ColorStone950
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Your identity has been successfully verified using your mobile driver's license, and a confirmation has been sent to your email.",
                fontFamily = Inter,
                fontSize = 18.sp,
                color = ColorStone600,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun KioskFailureView(onStartOver: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBase1)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            KioskHeader()

            Spacer(modifier = Modifier.weight(1f))

            // Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .shadow(4.dp, CircleShape)
                    .background(
                        brush = Brush.verticalGradient(listOf(ColorRose50, ColorRose200)),
                        shape = CircleShape
                    )
                    .border(2.dp, ColorRose300, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Invalid",
                    modifier = Modifier.size(40.dp),
                    tint = ColorRose600
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Invalid",
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                color = ColorRose700
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Your ID has not been accepted. Please try again or check in with reception for verification.",
                fontFamily = Inter,
                fontSize = 18.sp,
                color = ColorStone600,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onStartOver,
                shape = RoundedCornerShape(30.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorBlue100,
                    contentColor = ColorBlue800
                ),
                border = BorderStroke(1.dp, ColorBlue400),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Start over",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}
