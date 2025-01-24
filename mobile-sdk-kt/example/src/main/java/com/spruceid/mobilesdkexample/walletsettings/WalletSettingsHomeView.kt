package com.spruceid.mobilesdkexample.walletsettings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.generateTestMdl
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Composable
fun WalletSettingsHomeView(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel
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
            walletActivityLogsViewModel = walletActivityLogsViewModel
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
    walletActivityLogsViewModel: WalletActivityLogsViewModel
) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .padding(top = 10.dp)
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable {
                    navController.navigate(Screen.WalletSettingsActivityLogScreen.route)
                },
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.verification_activity_log),
                            contentDescription = stringResource(id = R.string.verification_activity_log),
                            modifier = Modifier.padding(end = 5.dp),
                        )
                        Text(
                            text = "Activity Log",
                            fontFamily = Inter,
                            fontWeight = FontWeight.Medium,
                            fontSize = 17.sp,
                            color = ColorStone950,
                            modifier = Modifier.padding(bottom = 5.dp, top = 5.dp),
                        )
                    }

                    Image(
                        painter = painterResource(id = R.drawable.chevron),
                        contentDescription = stringResource(id = R.string.chevron),
                        modifier = Modifier.scale(0.5f)
                    )
                }

                Text(
                    text = "View and export activity history",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 15.sp,
                    color = ColorStone600,
                )
            }
        }

        // Uncomment to add test mDL generation button to the settings page.
        GenerateMockMdocButton(credentialPacksViewModel = credentialPacksViewModel)

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

@Composable
fun GenerateMockMdocButton(
    credentialPacksViewModel: CredentialPacksViewModel
) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .clickable {
                scope.launch {
                    val keyManager = KeyManager()
                    val keyAlias = "testMdl"
                    if (!keyManager.keyExists(keyAlias)) {
                        keyManager.generateSigningKey(keyAlias)
                    }
                    val mdl = generateTestMdl(KeyManager(), keyAlias)
                    val mdocPack =
                        try {
                            credentialPacksViewModel.credentialPacks.value.first { pack ->
                                pack
                                    .list()
                                    .any { credential -> credential.asMsoMdoc() != null }
                            }
                        } catch (error: NoSuchElementException) {
                            CredentialPack()
                        }
                    if (mdocPack
                            .list()
                            .isEmpty()
                    ) {
                        mdocPack.addMdoc(mdl);
                        credentialPacksViewModel.saveCredentialPack(mdocPack)
                    } else {
                        Toast.showWarning("You already have an mDL")
                    }
                }
            },
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.unknown),
                        contentDescription = stringResource(id = R.string.unknown_check),
                        modifier = Modifier.padding(end = 5.dp),
                    )
                    Text(
                        text = "Generate mDL",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp,
                        color = ColorStone950,
                        modifier = Modifier.padding(bottom = 5.dp, top = 5.dp),
                    )
                }

                Image(
                    painter = painterResource(id = R.drawable.chevron),
                    contentDescription = stringResource(id = R.string.chevron),
                    modifier = Modifier.scale(0.5f)
                )
            }

            Text(
                text = "Generate a fresh test mDL issued by the SpruceID Test CA",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                color = ColorStone600,
            )
        }
    }
}
