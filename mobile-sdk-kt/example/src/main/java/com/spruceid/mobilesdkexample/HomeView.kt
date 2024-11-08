package com.spruceid.mobilesdkexample

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.ui.theme.Bg
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.verifier.VerifierHomeView
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import com.spruceid.mobilesdkexample.wallet.WalletHomeView

enum class HomeTabs {
    WALLET, VERIFIER
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun HomeView(
    navController: NavController,
    verificationMethodsViewModel: VerificationMethodsViewModel,
    credentialPacksViewModel: CredentialPacksViewModel
) {
    var tab by remember {
        mutableStateOf(HomeTabs.WALLET)
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bg), horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { tab = HomeTabs.WALLET },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = if (tab == HomeTabs.WALLET) Color.Blue else Color.Gray,
                    )
                ) {
                    Text(
                        text = "Wallet",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 10.sp,
                    )
                }

                Button(
                    onClick = { tab = HomeTabs.VERIFIER },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = if (tab == HomeTabs.VERIFIER) Color.Blue else Color.Gray,
                    )
                ) {
                    Text(
                        text = "Verifier",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        modifier = Modifier.navigationBarsPadding()
    ) {
        Box(modifier = Modifier.padding(bottom = 30.dp)) {
            if (tab == HomeTabs.WALLET) {
                WalletHomeView(
                    navController,
                    credentialPacksViewModel = credentialPacksViewModel
                )
            } else {
                VerifierHomeView(
                    navController = navController,
                    verificationMethodsViewModel = verificationMethodsViewModel
                )
            }
        }
    }
}
