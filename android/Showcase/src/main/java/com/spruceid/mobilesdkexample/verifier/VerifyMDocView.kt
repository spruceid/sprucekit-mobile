package com.spruceid.mobilesdkexample.verifier

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.BLESessionStateDelegate
import com.spruceid.mobile.sdk.IsoMdlReader
import com.spruceid.mobile.sdk.getBluetoothManager
import com.spruceid.mobile.sdk.getPermissions
import com.spruceid.mobile.sdk.nfc.NfcReaderEngagement
import com.spruceid.mobile.sdk.rs.AuthenticationStatus
import com.spruceid.mobile.sdk.rs.MDocItem
import com.spruceid.mobile.sdk.rs.ReaderHandover
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBase600
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.checkAndRequestBluetoothPermissions
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.TrustedCertificatesViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationActivityLogsViewModel
import kotlinx.coroutines.launch

val defaultElements: Map<String, Map<String, Boolean>> =
    mapOf(
        "org.iso.18013.5.1" to mapOf(
            // Mandatory
            "family_name" to false,
            "given_name" to false,
            "birth_date" to false,
            "issue_date" to false,
            "expiry_date" to false,
            "issuing_country" to false,
            "issuing_authority" to false,
            "document_number" to false,
            "portrait" to false,
            "driving_privileges" to false,
            // Optional
            "middle_name" to false,
            "birth_place" to false,
            "resident_address" to false,
            "height" to false,
            "weight" to false,
            "eye_colour" to false,
            "hair_colour" to false,
            "organ_donor" to false,
            "sex" to false,
            "nationality" to false,
            "place_of_issue" to false,
            "signature" to false,
            "phone_number" to false,
            "email_address" to false,
            "emergency_contact" to false,
            "vehicle_class" to false,
            "endorsements" to false,
            "restrictions" to false,
            "barcode_data" to false,
            "card_design_issuer" to false,
            "card_expiry_date" to false,
            "time_of_issue" to false,
            "time_of_expiry" to false,
            "portrait_capture_date" to false,
            "signature_capture_date" to false,
            "document_discriminator" to false,
            "audit_information" to false,
            "compliance_type" to false,
            "permit_identifier" to false,
            "veteran_indicator" to false,
            "resident_city" to false,
            "resident_postal_code" to false,
            "resident_state" to false,
            "issuing_jurisdiction" to false,
            "age_over_18" to false,
            "age_over_21" to false,
        ),
        "org.iso.18013.5.1.aamva" to mapOf(
            "DHS_compliance" to false,
            "DHS_temporary_lawful_status" to false,
            "real_id" to false,
            "jurisdiction_version" to false,
            "jurisdiction_id" to false,
            "organ_donor" to false,
            "domestic_driving_privileges" to false,
            "veteran" to false,
            "sex" to false,
            "name_suffix" to false
        )
    )

val ageOver18Elements: Map<String, Map<String, Boolean>> =
    mapOf(
        "org.iso.18013.5.1" to mapOf(
            "age_over_18" to false,
        )
    )

enum class State {
    ENABLE_BLUETOOTH,
    SCANNING,
    TRANSMITTING,
    DONE
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalPermissionsApi::class
)
@Composable
fun VerifyMDocView(
    navController: NavController,
    checkAgeOver18: Boolean = false
) {
    val verificationActivityLogsViewModel: VerificationActivityLogsViewModel =
        activityHiltViewModel()
    val trustedCertificatesViewModel: TrustedCertificatesViewModel = activityHiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reader: IsoMdlReader? = null

    var scanProcessState by remember {
        mutableStateOf(State.ENABLE_BLUETOOTH)
    }

    var result by remember { mutableStateOf<Map<String, Map<String, MDocItem>>?>(null) }
    var docTypes by remember { mutableStateOf<List<String>>(emptyList()) }
    var issuerAuthenticationStatus by remember { mutableStateOf<AuthenticationStatus?>(null) }
    var deviceAuthenticationStatus by remember { mutableStateOf<AuthenticationStatus?>(null) }
    var responseProcessingErrors by remember { mutableStateOf<String?>(null) }

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
            scanProcessState = State.SCANNING
        }
    }

    val bleCallback: BLESessionStateDelegate = object : BLESessionStateDelegate() {
        override fun update(state: Map<String, Any>) {
            if (state.containsKey("mdl")) {
                val response = reader?.handleMdlReaderResponseData(state["mdl"] as ByteArray)
                if (response != null) {
                    result = response.verifiedResponse
                    docTypes = response.docTypes
                    issuerAuthenticationStatus = response.issuerAuthentication
                    deviceAuthenticationStatus = response.deviceAuthentication
                    responseProcessingErrors = response.errors
                }
                scanProcessState = State.DONE
            }
        }

        override fun error(error: Exception) {
            TODO("Not yet implemented")
        }
    }

    fun back() {
        navController.navigate(
            Screen.HomeScreen.route.replace("{tab}", "verifier")
        ) {
            popUpTo(0)
        }
    }

    fun onHandover(handover: ReaderHandover) {
        scanProcessState = State.TRANSMITTING
        checkAndRequestBluetoothPermissions(
            context.applicationContext,
            getPermissions().toTypedArray(),
            launcherMultiplePermissions
        )
        val bluetooth = getBluetoothManager(context.applicationContext)
        scope.launch {
            try {
                reader = IsoMdlReader(
                    bleCallback,
                    handover,
                    if (checkAgeOver18) {
                        ageOver18Elements
                    } else {
                        defaultElements
                    },
                    trustedCertificatesViewModel.trustedCertificates.value.map {
                        it.content
                    },
                    bluetooth!!,
                    context.applicationContext
                )
            } catch (e: Exception) {
                e.localizedMessage?.let { Toast.showError(it) }
                back()
            }

        }
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    val activity = context as ComponentActivity
    var nfcUi by remember { mutableStateOf<NfcTabUi>(NfcTabUi.WaitingForTag) }
    val engagement = remember(activity) {
        NfcReaderEngagement(activity) { event ->
            when (event) {
                is NfcReaderEngagement.Event.WaitingForTag ->
                    nfcUi = NfcTabUi.WaitingForTag
                is NfcReaderEngagement.Event.Exchanging ->
                    nfcUi = NfcTabUi.Exchanging
                is NfcReaderEngagement.Event.TransientError -> {
                    // Recoverable; the SDK emits WaitingForTag right after.
                }
                is NfcReaderEngagement.Event.ProtocolError ->
                    nfcUi = NfcTabUi.ProtocolError(
                        event.cause.localizedMessage
                            ?: event.cause.message
                            ?: "Handover failed"
                    )
                is NfcReaderEngagement.Event.Success ->
                    onHandover(event.handover)
            }
        }
    }

    LaunchedEffect(Unit) {
        nfcUi = when {
            !engagement.isSupported -> NfcTabUi.NfcUnsupported
            !engagement.isEnabled -> NfcTabUi.NfcDisabled
            else -> NfcTabUi.WaitingForTag
        }
    }

    // Reader mode is on for the entire VerifyMDocView lifecycle. While on,
    // the device is in initiator role only — its HCE controller is dormant,
    // so foreign HCE services (payment apps, our own NfcPresentationService),
    // wallet pickers, and OS-level tag dispatchers (e.g. Samsung's "tag
    // scanner" overlay) all stay quiet for the duration of the verifier flow.
    DisposableEffect(Unit) {
        engagement.start()
        onDispose { engagement.stop() }
    }

    // Only actually run the APDU exchange while the user is on the NFC tab
    // and we're still in SCANNING. In every other case (QR tab, BT prompt,
    // BLE transmission, results screen) detected tags are silently swallowed.
    LaunchedEffect(scanProcessState, pagerState.settledPage) {
        engagement.engageOnTap = scanProcessState == State.SCANNING &&
            pagerState.settledPage == 1
    }

    when (scanProcessState) {
        State.ENABLE_BLUETOOTH -> if (!isBluetoothEnabled) {
            Box(
                Modifier
                    .padding(vertical = 40.dp)
                    .padding(horizontal = 30.dp)
                    .navigationBarsPadding()
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "Enable Bluetooth to start",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Button(
                        onClick = ::back,
                        shape = RoundedCornerShape(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.Black,
                        ),
                        border = BorderStroke(1.dp, ColorStone300),
                        modifier = Modifier
                            .fillMaxWidth()
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

        State.SCANNING -> Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                HorizontalPager(state = pagerState) { page ->
                    when (page) {
                        0 -> ScanningComponent(
                            ScanningType.QRCODE,
                            title = "",
                            subtitle = "",
                            onRead = { uri -> onHandover(ReaderHandover.newQr(uri)) },
                            onCancel = ::back,
                        )
                        1 -> VerifyMDocNfcTab(
                            nfcUi = nfcUi,
                            onRetry = { nfcUi = NfcTabUi.WaitingForTag },
                            onCancel = ::back,
                        )
                    }
                }
            }
            VerifyMDocEngagementTabs(pagerState) { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            }
        }

        State.TRANSMITTING -> LoadingView("Verifying...", "Cancel", ::back)
        State.DONE -> VerifierMDocResultView(
            result = result!!,
            docTypes = docTypes,
            issuerAuthenticationStatus = issuerAuthenticationStatus ?: AuthenticationStatus.UNCHECKED,
            deviceAuthenticationStatus = deviceAuthenticationStatus ?: AuthenticationStatus.UNCHECKED,
            responseProcessingErrors = responseProcessingErrors,
            onClose = ::back,
            logVerification = { title, issuer, status ->
                scope.launch {
                    verificationActivityLogsViewModel.saveVerificationActivityLog(
                        VerificationActivityLogs(
                            credentialTitle = title,
                            issuer = issuer,
                            status = status,
                            verificationDateTime = getCurrentSqlDate(),
                            additionalInformation = ""
                        )
                    )
                }
            }
        )
    }
}

@Composable
private fun VerifyMDocEngagementTabs(
    pagerState: PagerState,
    changeTab: (Int) -> Unit,
) {
    val icons = listOf(
        R.drawable.qrcode_scanner to "QR code engagement",
        R.drawable.wallet to "NFC engagement",
    )
    BottomAppBar(containerColor = ColorBase50) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            icons.forEachIndexed { index, (drawable, alt) ->
                Button(
                    onClick = { changeTab(index) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                ) {
                    val active = pagerState.currentPage == index
                    Image(
                        painter = painterResource(id = drawable),
                        contentDescription = alt,
                        colorFilter = ColorFilter.tint(
                            if (active) ColorBlue600 else ColorBase600,
                        ),
                        modifier = Modifier
                            .width(32.dp)
                            .height(32.dp)
                            .padding(end = 3.dp)
                            .drawBehind {
                                drawLine(
                                    color = if (active) ColorBlue600 else Color.Transparent,
                                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                                    strokeWidth = 4.dp.toPx(),
                                )
                            },
                    )
                }
            }
        }
    }
}
