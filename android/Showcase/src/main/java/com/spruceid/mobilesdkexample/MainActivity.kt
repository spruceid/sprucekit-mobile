package com.spruceid.mobilesdkexample

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.spruceid.mobile.sdk.ConnectionLiveData
import com.spruceid.mobile.sdk.nfc.NfcListenManager
import com.spruceid.mobilesdkexample.credentials.NfcPresentationService
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.navigation.SetupNavGraph
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme
import com.spruceid.mobilesdkexample.utils.ModalBottomSheetHost
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.wallet.ApplySpruceMdlConfirmation
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

const val DEFAULT_SIGNING_KEY_ID = "reference-app/default-signing"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var navController: NavHostController
    private lateinit var connectionLiveData: ConnectionLiveData
    private lateinit var hacApplicationsViewModel: HacApplicationsViewModel

    override fun onNewIntent(intent: Intent) {
        if (intent.action == "android.intent.action.VIEW" && intent.data != null) {
            if (intent.data!!.toString().startsWith("spruceid://?sd-jwt=")) {
                navController.navigate(
                    Screen.AddToWalletScreen.route.replace(
                        "{rawCredential}",
                        intent.data.toString().replace("spruceid://?sd-jwt=", "")
                    )
                )
            } else if (intent.data!!.toString().startsWith("spruceid://?spruceid-mdl=")) {
                GlobalScope.launch {
                    val id = intent.data.toString().replace("spruceid://?spruceid-mdl=", "")
                    val application = hacApplicationsViewModel.getApplicationByIssuanceId(id)
                    application?.let {
                        hacApplicationsViewModel.updateIssuanceState(
                            application.id,
                            application.issuanceId
                        )
                        ModalBottomSheetHost.show(
                            content = {
                                ApplySpruceMdlConfirmation(
                                    application = application,
                                    hacApplicationsViewModel = hacApplicationsViewModel
                                ) {
                                    ModalBottomSheetHost.hide()
                                }
                            }
                        )
                    }
                }
            } else if (intent.data!!.toString().startsWith("openid4vp")) {
                navController.navigate(
                    Screen.HandleOID4VP.route.replace(
                        "{url}",
                        intent.data.toString().replace("openid4vp://", "")
                    )
                )
            } else if (intent.data!!.toString().startsWith("openid-credential-offer")) {
                navController.navigate(
                    Screen.HandleOID4VCI.route.replace(
                        "{url}",
                        intent.data.toString().replace("openid-credential-offer://", "")
                    )
                )
            } else if (intent.data!!.toString().startsWith("mdoc-openid4vp")) {
                navController.navigate(
                    Screen.HandleMdocOID4VP.route.replace(
                        "{url}",
                        intent.data.toString().replace("mdoc-openid4vp://", "")
                    )
                )
            }
        } else {
            super.onNewIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()

        NfcAdapter.getDefaultAdapter(this)?.let {
            val cardEmulation = CardEmulation.getInstance(it)
            if(!cardEmulation.setPreferredService(this, ComponentName(this, NfcPresentationService::class.java))) {
                Log.e("MainActivity", "cardEmulation.setPreferredService() failed")
            }
        }
    }
    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.let {
            val cardEmulation = CardEmulation.getInstance(it)
            if (!cardEmulation.unsetPreferredService(this)) {
                Log.e("MainActivity", "cardEmulation.unsetPreferredService() failed")
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Enables the NFC presentation UI to control what NFC messages the app is listening for
        NfcListenManager.init(
                applicationContext,
                ComponentName(applicationContext, NfcPresentationService::class.java)
        )

        enableEdgeToEdge()
        setContent {
            MobileSdkTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = ColorBase1,
                ) {
                    navController = rememberNavController()

                    hacApplicationsViewModel = activityHiltViewModel()
                    val statusListViewModel: StatusListViewModel = activityHiltViewModel()
                    connectionLiveData = ConnectionLiveData(this)
                    connectionLiveData.observe(this) { isNetworkAvailable ->
                        isNetworkAvailable?.let {
                            statusListViewModel.setHasConnection(it)
                        }
                    }

                    SetupNavGraph(navController)
                }
                // Global Toast Host
                Toast.Host()

                // Global Modal Bottom Sheet Host
                ModalBottomSheetHost.Host()
            }
        }
    }
}

@HiltAndroidApp
class MainApplication : Application()