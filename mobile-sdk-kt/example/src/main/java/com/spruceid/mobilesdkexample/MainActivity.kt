package com.spruceid.mobilesdkexample

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobilesdkexample.db.AppDatabase
import com.spruceid.mobilesdkexample.db.RawCredentialsRepository
import com.spruceid.mobilesdkexample.navigation.SetupNavGraph
import com.spruceid.mobilesdkexample.ui.theme.Bg
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme
import com.spruceid.mobilesdkexample.viewmodels.IRawCredentialsViewModel
import com.spruceid.mobilesdkexample.viewmodels.RawCredentialsViewModelFactory
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

const val DEFAULT_KEY_ID = "key-1"

class MainActivity : ComponentActivity() {
    private lateinit var navController: NavHostController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            MobileSdkTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = Bg,
                ) {
                    navController = rememberNavController()

                    val credentialsViewModel: IRawCredentialsViewModel by viewModels {
                        RawCredentialsViewModelFactory((application as MainApplication).rawCredentialsRepository)
                    }

                    // Insert a raw credential into the rawCredentialsRepository,
                    // using a suspend / async method.
                    LaunchedEffect(credentialsViewModel) {
                        lifecycle.coroutineScope.launch {
                            // Setup a default keyId for the RequestSigner.
                            // Check the key manager if the key exists, if not, create it.
                            val km = KeyManager()

                            if (!km.keyExists(DEFAULT_KEY_ID)) {
                                // Key does not exist, create it.
                                km.generateSigningKey(DEFAULT_KEY_ID)
                            }


//                            // Clear the raw credentials table.
//                            credentialsViewModel.deleteAllRawCredentials()
//                            // Load the exampleSdJwt into the raw credentials table.
//                            credentialsViewModel.saveRawCredential(
//                                com.spruceid.mobilesdkexample.db.RawCredentials(
//                                    rawCredential = exampleSdJwt
//                                )
//                            )
                        }
                    }

                    SetupNavGraph(navController, credentialsViewModel)
                }
            }
        }
    }
}

class MainApplication : Application() {
    val db by lazy { AppDatabase.getDatabase(applicationContext) }
    val rawCredentialsRepository by lazy { RawCredentialsRepository(db.rawCredentialsDao()) }
}