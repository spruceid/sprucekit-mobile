package com.spruceid.mobilesdkexample.walletsettings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.config.EnvironmentConfig
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.SettingsHomeItem
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Composable
fun WalletSettingsHomeView(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    hacApplicationsViewModel: HacApplicationsViewModel
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        WalletSettingsHomeHeader(
            onBack = {
                navController.navigate(
                    Screen.HomeScreen.route.replace("{tab}", "wallet")
                ) {
                    popUpTo(0)
                }
            }
        )
        WalletSettingsHomeBody(
            navController = navController,
            credentialPacksViewModel = credentialPacksViewModel,
            walletActivityLogsViewModel = walletActivityLogsViewModel,
            hacApplicationsViewModel = hacApplicationsViewModel
        )
    }
}

@Composable
fun WalletSettingsHomeHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Preferences",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = ColorStone950
        )
        Spacer(Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(36.dp)
                .height(36.dp)
                .padding(start = 4.dp)
                .clip(shape = RoundedCornerShape(8.dp))
                .background(ColorStone950)
                .clickable {
                    onBack()
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.user),
                contentDescription = stringResource(id = R.string.user),
                colorFilter = ColorFilter.tint(ColorStone50),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
        }
    }
}

@Composable
fun WalletSettingsHomeBody(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    hacApplicationsViewModel: HacApplicationsViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isApplyingForMdl by remember { mutableStateOf(false) }
    val isDevMode by EnvironmentConfig.isDevMode.collectAsState()

    Column(
        Modifier
            .padding(top = 10.dp)
            .navigationBarsPadding(),
    ) {
        SettingsHomeItem(
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.verification_activity_log),
                    contentDescription = stringResource(id = R.string.verification_activity_log),
                    modifier = Modifier.padding(end = 5.dp),
                )
            },
            name = "Activity Log",
            description = "View and export activity history",
            action = {
                navController.navigate(Screen.WalletSettingsActivityLogScreen.route)
            }
        )

        GenerateMockMdlButton(credentialPacksViewModel = credentialPacksViewModel)

        SettingsHomeItem(
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.apply_spruceid_mdl),
                    contentDescription = stringResource(id = R.string.apply_spruceid_mdl),
                    modifier = Modifier.padding(end = 5.dp),
                )
            },
            name = "Apply for Spruce mDL",
            description = "Verify your identity in order to claim this high assurance credential",
            enabled = !isApplyingForMdl,
            action = {
                scope.launch {
                    isApplyingForMdl = true
                    try {
                        val walletAttestation = hacApplicationsViewModel.getWalletAttestation()
                        walletAttestation?.let {
                            val issuance =
                                hacApplicationsViewModel.issuanceClient
                                    .newIssuance(walletAttestation)

                            val hacApplication = hacApplicationsViewModel.saveApplication(
                                HacApplications(issuanceId = issuance)
                            )

                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("${EnvironmentConfig.proofingClientUrl}/proofing?id=${hacApplication}&redirect=spruceid")
                            )

                            context.startActivity(intent)
                        }
                    } finally {
                        isApplyingForMdl = false
                    }
                }
            }
        )

        SettingsHomeItem(
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.dev_mode),
                    contentDescription = stringResource(id = R.string.dev_mode),
                    modifier = Modifier.padding(end = 5.dp),
                )
            },
            name = "${if (isDevMode) "Disable" else "Enable"} Dev Mode",
            description = "Warning: Dev mode will use in development services and is not recommended for production use",
            action = {
                EnvironmentConfig.toggleDevMode()
            }
        )

        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                GlobalScope.launch {
                    credentialPacksViewModel.deleteAllCredentialPacks(onDeleteCredentialPack = { credentialPack ->
                        credentialPack.list().forEach { credential ->
                            val credentialInfo =
                                getCredentialIdTitleAndIssuer(
                                    credentialPack,
                                    credential
                                )
                            walletActivityLogsViewModel.saveWalletActivityLog(
                                walletActivityLogs = WalletActivityLogs(
                                    credentialPackId = credentialPack.id().toString(),
                                    credentialId = credentialInfo.first,
                                    credentialTitle = credentialInfo.second,
                                    issuer = credentialInfo.third,
                                    action = "Deleted",
                                    dateTime = getCurrentSqlDate(),
                                    additionalInformation = ""
                                )
                            )
                        }
                    })
                    hacApplicationsViewModel.deleteAllApplications()
                }
            },
            shape = RoundedCornerShape(5.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ColorRose600,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 30.dp)
        ) {
            Text(
                text = "Delete all added credentials",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}
