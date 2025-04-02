package com.spruceid.mobilesdkexample

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue300
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue500
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue900
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import com.spruceid.mobilesdkexample.verifier.VerifierHomeView
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import com.spruceid.mobilesdkexample.wallet.WalletHomeView

enum class HomeTabs {
    WALLET, VERIFIER
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun HomeView(
    navController: NavController,
    initialTab: String,
    verificationMethodsViewModel: VerificationMethodsViewModel,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    statusListViewModel: StatusListViewModel,
    helpersViewModel: HelpersViewModel,
    hacApplicationsViewModel: HacApplicationsViewModel
) {
    var tab by remember {
        if (initialTab == "verifier") {
            mutableStateOf(HomeTabs.VERIFIER)
        } else {
            mutableStateOf(HomeTabs.WALLET)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            HomeBottomTabs(tab) { newTab ->
                tab = newTab
            }
        },
        modifier = Modifier.navigationBarsPadding()
    ) {
        Box(modifier = Modifier.padding(bottom = 60.dp)) {
            if (tab == HomeTabs.WALLET) {
                WalletHomeView(
                    navController,
                    credentialPacksViewModel = credentialPacksViewModel,
                    walletActivityLogsViewModel = walletActivityLogsViewModel,
                    statusListViewModel = statusListViewModel,
                    helpersViewModel = helpersViewModel,
                    hacApplicationsViewModel = hacApplicationsViewModel
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

@Composable
fun HomeBottomTabs(
    tab: HomeTabs,
    changeTabs: (HomeTabs) -> Unit
) {
    BottomAppBar(containerColor = ColorBase1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(shape = RoundedCornerShape(14.dp))
                    .background(ColorBlue900)
                    .padding(horizontal = 4.dp)
            ) {
                Button(
                    onClick = { changeTabs(HomeTabs.WALLET) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (tab == HomeTabs.WALLET) ColorBlue500 else Color.Transparent,
                        contentColor = if (tab == HomeTabs.WALLET) Color.White else ColorBlue300,
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.wallet),
                            contentDescription = stringResource(id = R.string.wallet),
                            colorFilter = ColorFilter.tint(
                                if (tab == HomeTabs.WALLET) Color.White else ColorBlue300,
                            ),
                            modifier = Modifier
                                .width(20.dp)
                                .height(20.dp)
                                .padding(end = 3.dp)
                        )
                        Text(
                            text = "Wallet",
                            fontFamily = Switzer,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                        )
                    }
                }

                Button(
                    onClick = { changeTabs(HomeTabs.VERIFIER) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (tab == HomeTabs.VERIFIER) ColorBlue500 else Color.Transparent,
                        contentColor = if (tab == HomeTabs.VERIFIER) Color.White else ColorBlue300,
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.qrcode_scanner),
                            contentDescription = stringResource(id = R.string.verifier),
                            colorFilter = ColorFilter.tint(
                                if (tab == HomeTabs.VERIFIER) Color.White else ColorBlue300,
                            ),
                            modifier = Modifier
                                .width(20.dp)
                                .height(20.dp)
                                .padding(end = 3.dp)
                        )
                        Text(
                            text = "Verifier",
                            fontFamily = Switzer,
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
