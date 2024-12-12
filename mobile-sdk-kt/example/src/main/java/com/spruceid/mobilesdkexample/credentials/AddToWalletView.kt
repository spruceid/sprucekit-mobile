package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.ErrorView
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald700
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun AddToWalletView(
    navController: NavHostController,
    rawCredential: String,
    credentialPacksViewModel: CredentialPacksViewModel
) {
    var credentialItem by remember { mutableStateOf<ICredentialView?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var storing by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        credentialItem = credentialDisplaySelector(rawCredential, null, null)
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
                    credentialPack.tryAddRawCredential(rawCredential)
                    credentialPacksViewModel.saveCredentialPack(credentialPack)
                } catch (e: Exception) {
                    error = e.localizedMessage
                }
            }.await()
            if (error == null) {
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
        Column(
            Modifier
                .padding(all = 20.dp)
                .padding(top = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "Review Info",
                textAlign = TextAlign.Center,
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = ColorStone950,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
            )

            credentialItem!!.credentialListItem()

            Column(
                Modifier
                    .fillMaxSize()
                    .weight(weight = 1f, fill = false)
            ) {
                credentialItem!!.credentialDetails()
            }

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
        }
    }

}
