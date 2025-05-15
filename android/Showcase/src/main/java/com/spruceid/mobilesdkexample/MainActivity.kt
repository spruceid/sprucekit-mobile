package com.spruceid.mobilesdkexample

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.spruceid.mobile.sdk.ConnectionLiveData
import com.spruceid.mobile.sdk.dcapi.Registry
import com.spruceid.mobilesdkexample.db.AppDatabase
import com.spruceid.mobilesdkexample.db.HacApplicationsRepository
import com.spruceid.mobilesdkexample.db.TrustedCertificatesRepository
import com.spruceid.mobilesdkexample.db.VerificationActivityLogsRepository
import com.spruceid.mobilesdkexample.db.VerificationMethodsRepository
import com.spruceid.mobilesdkexample.db.WalletActivityLogsRepository
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.navigation.SetupNavGraph
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme
import com.spruceid.mobilesdkexample.utils.ModalBottomSheetHost
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModelFactory
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModelFactory
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.TrustedCertificatesViewModel
import com.spruceid.mobilesdkexample.viewmodels.TrustedCertificatesViewModelFactory
import com.spruceid.mobilesdkexample.viewmodels.VerificationActivityLogsViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationActivityLogsViewModelFactory
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModelFactory
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModelFactory
import com.spruceid.mobilesdkexample.wallet.ApplySpruceMdlConfirmation

const val DEFAULT_SIGNING_KEY_ID = "reference-app/default-signing"

class MainActivity : ComponentActivity() {
    private lateinit var navController: NavHostController
    private lateinit var connectionLiveData: ConnectionLiveData

    override fun onNewIntent(intent: Intent?) {
        if (intent != null && intent.action == "android.intent.action.VIEW" && intent.data != null) {
            if (intent.data!!.toString().startsWith("spruceid://?sd-jwt=")) {
                navController.navigate(
                    Screen.AddToWalletScreen.route.replace(
                        "{rawCredential}",
                        intent.data.toString().replace("spruceid://?sd-jwt=", "")
                    )
                )
            } else if (intent.data!!.toString().startsWith("spruceid://?spruceid-mdl=")) {
                ModalBottomSheetHost.show(
                    content = {
                        ApplySpruceMdlConfirmation {
                            ModalBottomSheetHost.hide()
                        }
                    }
                )
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

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        enableEdgeToEdge()
        setContent {
            MobileSdkTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = ColorBase1,
                ) {
                    navController = rememberNavController()


                    // TODO: Completely remove RawCredentialsViewModel after confirming if credentials will be migrated
                    // val credentialsViewModel: IRawCredentialsViewModel by viewModels {
                    //    RawCredentialsViewModelFactory((application as MainApplication).rawCredentialsRepository)
                    // }

                    val verificationMethodsViewModel: VerificationMethodsViewModel by viewModels {
                        VerificationMethodsViewModelFactory((application as MainApplication).verificationMethodsRepository)
                    }

                    val verificationActivityLogsViewModel: VerificationActivityLogsViewModel by viewModels {
                        VerificationActivityLogsViewModelFactory((application as MainApplication).verificationActivityLogsRepository)
                    }

                    val walletActivityLogsViewModel: WalletActivityLogsViewModel by viewModels {
                        WalletActivityLogsViewModelFactory((application as MainApplication).walletActivityLogsRepository)
                    }

                    val credentialPacksViewModel: CredentialPacksViewModel by viewModels {
                        CredentialPacksViewModelFactory(application as MainApplication)
                    }

                    val trustedCertificatesViewModel: TrustedCertificatesViewModel by viewModels {
                        TrustedCertificatesViewModelFactory((application as MainApplication).trustedCertificatesRepository)
                    }

                    val hacApplicationsViewModel: HacApplicationsViewModel by viewModels {
                        HacApplicationsViewModelFactory(
                            application as MainApplication,
                            (application as MainApplication).hacApplicationsRepository
                        )
                    }

                    val statusListViewModel: StatusListViewModel by viewModels<StatusListViewModel>()
                    connectionLiveData = ConnectionLiveData(this)
                    connectionLiveData.observe(this) { isNetworkAvailable ->
                        isNetworkAvailable?.let {
                            statusListViewModel.setHasConnection(it)
                        }
                    }

                    val helpersViewModel: HelpersViewModel by viewModels<HelpersViewModel>()

                    SetupNavGraph(
                        navController,
                        verificationMethodsViewModel = verificationMethodsViewModel,
                        verificationActivityLogsViewModel = verificationActivityLogsViewModel,
                        walletActivityLogsViewModel = walletActivityLogsViewModel,
                        credentialPacksViewModel = credentialPacksViewModel,
                        statusListViewModel = statusListViewModel,
                        helpersViewModel = helpersViewModel,
                        trustedCertificatesViewModel = trustedCertificatesViewModel,
                        hacApplicationsViewModel = hacApplicationsViewModel
                    )
                }
                // Global Toast Host
                Toast.Host()

                // Global Modal Bottom Sheet Host
                ModalBottomSheetHost.Host()
            }
        }
    }
}

class MainApplication : Application() {
    val db by lazy { AppDatabase.getDatabase(applicationContext) }
    // TODO: Completely remove RawCredentialsViewModel after confirming if credentials will be migrated
    // val rawCredentialsRepository by lazy { RawCredentialsRepository(db.rawCredentialsDao()) }

    val verificationMethodsRepository by lazy { VerificationMethodsRepository(db.verificationMethodsDao()) }
    val verificationActivityLogsRepository by lazy { VerificationActivityLogsRepository(db.verificationActivityLogsDao()) }
    val walletActivityLogsRepository by lazy { WalletActivityLogsRepository(db.walletActivityLogsDao()) }
    val trustedCertificatesRepository by lazy { TrustedCertificatesRepository(db.trustedCertificatesDao()) }
    val hacApplicationsRepository by lazy { HacApplicationsRepository(db.hacApplicationsDao()) }

    // DC-API integration
    val dcApiRegistry by lazy { Registry(this, "pinecone.ico") }
}