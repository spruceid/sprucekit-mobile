package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.DEFAULT_SIGNING_KEY_ID
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald700
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun AddToWalletView(
    navController: NavHostController,
    rawCredential: String,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    statusListViewModel: StatusListViewModel,
    onSuccess: (() -> Unit)? = null
) {
    var credentialItem by remember { mutableStateOf<ICredentialView?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var storing by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        credentialItem =
            credentialDisplaySelector(rawCredential, statusListViewModel, null, null, null)
    }

    fun back() {
        navController.navigate(Screen.HomeScreen.route) {
            popUpTo(0)
        }
    }

    fun saveCredential() {
        scope.launch {
            storing = true
            var error: String? = null
            this.async(Dispatchers.Default) {
                try {
                    val credentialPack = CredentialPack()

                    // Try add credential in any supported format
                    credentialPack.tryAddAnyFormat(rawCredential, DEFAULT_SIGNING_KEY_ID)

                    credentialPacksViewModel.saveCredentialPack(credentialPack)
                    val credentialInfo = getCredentialIdTitleAndIssuer(credentialPack)
                    walletActivityLogsViewModel.saveWalletActivityLog(
                        walletActivityLogs = WalletActivityLogs(
                            credentialPackId = credentialPack.id().toString(),
                            credentialId = credentialInfo.first,
                            credentialTitle = credentialInfo.second,
                            issuer = credentialInfo.third,
                            action = "Claimed",
                            dateTime = getCurrentSqlDate(),
                            additionalInformation = ""
                        )
                    )
                } catch (e: Exception) {
                    error = e.localizedMessage
                }
            }.await()
            if (error == null) {
                onSuccess?.invoke()
                back()
            } else {
                err = error
                storing = false
            }
        }
    }

    if (err != null) {
        ErrorView(
            errorTitle = "Error Adding Credential",
            errorDetails = err!!,
            onClose = {
                back()
            }
        )
    } else if (storing) {
        LoadingView(
            loadingText = "Storing credential..."
        )
    } else if (credentialItem != null) {
        credentialItem!!.credentialReviewInfo(footerActions = {
            Button(
                onClick = {
                    saveCredential()
                },
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorEmerald700,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Add to Wallet",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }

            Button(
                onClick = {
                    back()
                },
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorRose600,
                ),
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Close",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorRose600,
                )
            }
        })
    }
}
