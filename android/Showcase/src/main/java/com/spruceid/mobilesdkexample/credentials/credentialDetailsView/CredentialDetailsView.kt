package com.spruceid.mobilesdkexample.credentials.credentialDetailsView

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialsViewModel
import com.spruceid.mobile.sdk.getPermissions
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.credentials.CredentialOptionsDialogActions
import com.spruceid.mobilesdkexample.credentials.ICredentialView
import com.spruceid.mobilesdkexample.credentials.ShareMdocView
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.utils.ModalBottomSheetHost
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.credentialPackHasMdoc
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.utils.getFileContent
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.launch
import java.util.UUID

enum class CredentialMode {
    NONE,
    SCAN,
    SHARE
}

class CredentialDetailsViewTabs(
    val image: @Composable () -> Painter,
    val alt: @Composable () -> String
)

@Composable
fun CredentialDetailsView(
    navController: NavController,
    credentialPackId: String
) {
    val scope = rememberCoroutineScope()

    val credentialPacksViewModel: CredentialPacksViewModel = activityHiltViewModel()
    val credentialViewModel: CredentialsViewModel = activityHiltViewModel()
    val statusListViewModel: StatusListViewModel = activityHiltViewModel()
    val walletActivityLogsViewModel: WalletActivityLogsViewModel = activityHiltViewModel()
    val helpersViewModel: HelpersViewModel = activityHiltViewModel()


    var credentialTitle by remember { mutableStateOf<String?>(null) }
    var credentialItem by remember { mutableStateOf<ICredentialView?>(null) }
    var credentialPack by remember { mutableStateOf<CredentialPack?>(null) }
    val statusList by statusListViewModel.observeStatusForId(UUID.fromString(credentialPackId))
        .collectAsState()

    // Simple state management - start with Tap mode selected
    var currentMode by remember { mutableStateOf(CredentialMode.NONE) }
    // Show/Hide action menu for a credential
    var showBottomSheet by remember { mutableStateOf(false) }

    val isLoading by credentialPacksViewModel.loading.collectAsState()
    val credentialPacks by credentialPacksViewModel.credentialPacks.collectAsState()
    var hasMdocSupport by remember { mutableStateOf(false) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Check if this was a Bluetooth permission request
        val isBluetoothRequest = result.keys.containsAll(getPermissions())

        if (isBluetoothRequest) {
            val bluetoothGranted = getPermissions().all { result[it] == true }
            credentialViewModel.setBluetoothPermissionsGranted(bluetoothGranted)
            if (!bluetoothGranted) {
                // TODO: Show Bluetooth error or fallback
            }
        }

        // Check if this was a Camera permission request
        if (result.containsKey(Manifest.permission.CAMERA)) {
            val cameraGranted = result[Manifest.permission.CAMERA] == true
            if (!cameraGranted) {
                // TODO: Show camera error or fallback
            }
        }
    }

    fun back() {
        // Immediately disable scan mode to cleanup camera faster
        if (currentMode == CredentialMode.SCAN) {
            currentMode = CredentialMode.NONE
        }
        navController.popBackStack()
    }

    fun onDelete() {
        scope.launch {
            try {
                credentialPack?.let { pack ->
                    // Log activity before deletion
                    pack.list().forEach { credential ->
                        val credentialInfo = getCredentialIdTitleAndIssuer(pack, credential)
                        walletActivityLogsViewModel.saveWalletActivityLog(
                            WalletActivityLogs(
                                credentialPackId = credentialPackId,
                                credentialId = credentialInfo.first,
                                credentialTitle = credentialInfo.second,
                                issuer = credentialInfo.third ?: "",
                                action = "Deleted",
                                dateTime = getCurrentSqlDate(),
                                additionalInformation = ""
                            )
                        )
                    }

                    // Delete the credential pack
                    credentialPacksViewModel.deleteCredentialPack(pack)

                    // Show success toast
                    Toast.showSuccess("Credential deleted successfully")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // If deletion fails, maybe we should navigate back to avoid being stuck
                back()
            }
        }
    }

    fun onExport() {
        credentialPack?.let { pack ->
            helpersViewModel.exportText(
                getFileContent(pack),
                "$credentialTitle.json",
                "text/plain"
            )
        }

    }

    LaunchedEffect(isLoading, credentialPacks) {
        if (isLoading) {
            return@LaunchedEffect
        }

        credentialPack = credentialPacksViewModel.getById(credentialPackId)

        if (credentialPack == null) {
            back()
            return@LaunchedEffect
        }
        hasMdocSupport = credentialPackHasMdoc(credentialPack!!)

        try {
            credentialTitle = getCredentialIdTitleAndIssuer(credentialPack!!).second
            credentialItem = credentialDisplaySelector(
                credentialPack!!,
                statusListViewModel,
                null,
                null,
                null
            )
            statusListViewModel.fetchAndUpdateStatus(credentialPack!!)
        } catch (e: Exception) {
            e.printStackTrace()
            back()
        }
    }

    if (isLoading) {
        Column(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LoadingView(loadingText = "Loading credential details...")
        }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ColorBase50)
    ) {
        Column(
            Modifier
                .fillMaxSize()
        ) {
            // Main content with weight to push footer to bottom
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 60.dp)
            ) {
                // Credential card
                Column(
                    modifier = Modifier.padding(horizontal = 25.dp)
                ) {
                    if (currentMode == CredentialMode.SCAN || currentMode == CredentialMode.SHARE) {
                        // Compact version for scan and share modes
                        CompactCredentialInfo(
                            credentialPack = credentialPack
                        )
                    } else {
                        // Full credential card for non-selected mode
                        credentialItem?.credentialListItem(withOptions = false)
                    }
                }

                // Middle of the screen - takes remaining space
                Box(modifier = Modifier.weight(1f)) {
                    when (currentMode) {
                        CredentialMode.SCAN -> ScanModeContent(
                            navController,
                            credentialPackId
                        )

                        CredentialMode.SHARE -> ShareModeContent(credentialPack) { pack ->
                            GenericCredentialDetailsShareQRCode(
                                credentialPack = pack,
                            )
                        }

                        else -> {
                            // Default state - empty background
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                            ) {
                                Text(
                                    modifier = Modifier.align(Alignment.Center),
                                    text = "Scan to verify or share your credential",
                                    color = ColorStone400
                                )

                            }
                        }
                    }
                }
            }

            // Buttons + Close button (footer) - always visible at bottom
            CredentialDetailFooter(
                selectedTab = currentMode,
                hasShareSupport = hasMdocSupport,
                onScanClick = {
                    if (currentMode == CredentialMode.SCAN) {
                        // If already in Scan mode, go back to initial state
                        currentMode = CredentialMode.NONE
                    } else {
                        // Check camera permission before switching to Scan mode
                        permissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        currentMode = CredentialMode.SCAN // Switch to Scan mode
                    }
                },
                onShareClick = {
                    if (currentMode == CredentialMode.SHARE) {
                        // If already in Share mode, go back to initial state
                        currentMode = CredentialMode.NONE
                    } else {
                        currentMode = CredentialMode.SHARE // Switch to Share mode
                    }
                },
                onDetailsClick = {
                    ModalBottomSheetHost.show {
                        DetailsModal(
                            credentialItem = credentialItem,
                            statusList = statusList,
                            onClose = { ModalBottomSheetHost.hide() },
                            onBack = { back() }
                        )
                    }
                },
                onActivityLogClick = {
                    ModalBottomSheetHost.show {
                        ActivityLogModal(
                            credentialPackId = credentialPackId,
                            onClose = { ModalBottomSheetHost.hide() }
                        )
                    }
                },
                onMoreClick = {
                    showBottomSheet = true
                },
                onCloseClick = {
                    back()
                }
            )
        }
    }
    // Handle credential options dialog
    if (showBottomSheet) {
        CredentialOptionsDialogActions(
            setShowBottomSheet = { show ->
                showBottomSheet = show
            },
            onDelete = {
                showBottomSheet = false
                onDelete()
            },
            onExport = {
                showBottomSheet = false
                onExport()
            }


        )
    }
}

@Composable
fun GenericCredentialDetailsShareQRCode(credentialPack: CredentialPack) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    fun newCredentialViewModel(): CredentialsViewModel {
        val credentialViewModel = ViewModelProvider.AndroidViewModelFactory(application)
            .create(CredentialsViewModel::class.java)
        val parsedCredential: ParsedCredential? =
            credentialPack.list().firstNotNullOfOrNull { credential ->
                try {
                    if (credential.asMsoMdoc() != null) {
                        return@firstNotNullOfOrNull credential
                    }
                } catch (_: Exception) {
                }
                null
            }
        parsedCredential?.let {
            credentialViewModel.storeCredential(parsedCredential)
        }
        return credentialViewModel
    }

    val credentialViewModel by remember {
        mutableStateOf(newCredentialViewModel())
    }

    fun cancel() {
        credentialViewModel.cancel()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .clip(shape = RoundedCornerShape(12.dp))
                .background(ColorBase1)
                .border(
                    width = 1.dp,
                    color = ColorStone300,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(8.dp)
        ) {
            ShareMdocView(
                credentialViewModel = credentialViewModel,
                credentialPack = credentialPack,
                onCancel = {
                    cancel()
                }
            )
        }
   }
}
