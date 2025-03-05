package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase150
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.utils.getFileContent
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import kotlinx.coroutines.launch

@Composable
fun WalletHomeView(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    statusListViewModel: StatusListViewModel,
    helpersViewModel: HelpersViewModel
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        WalletHomeHeader(navController = navController)
        WalletHomeBody(
            navController = navController,
            credentialPacksViewModel = credentialPacksViewModel,
            helpersViewModel = helpersViewModel,
            walletActivityLogsViewModel = walletActivityLogsViewModel,
            statusListViewModel = statusListViewModel
        )
    }
}

@Composable
fun WalletHomeHeader(navController: NavController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Wallet",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = ColorStone950
        )
        Spacer(Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier =
            Modifier
                .width(36.dp)
                .height(36.dp)
                .padding(start = 4.dp)
                .clip(shape = RoundedCornerShape(8.dp))
                .background(ColorBase150)
                .clickable { navController.navigate(Screen.ScanQRScreen.route) }
        ) {
            Image(
                painter = painterResource(id = R.drawable.qrcode_scanner),
                contentDescription = stringResource(id = R.string.qrcode_scanner),
                colorFilter = ColorFilter.tint(ColorStone400),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
            Modifier
                .width(36.dp)
                .height(36.dp)
                .padding(start = 4.dp)
                .clip(shape = RoundedCornerShape(8.dp))
                .background(ColorBase150)
                .clickable {
                    navController.navigate(Screen.WalletSettingsHomeScreen.route)
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.user),
                contentDescription = stringResource(id = R.string.user),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
        }
    }
}

@Composable
fun WalletHomeBody(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    helpersViewModel: HelpersViewModel,
    statusListViewModel: StatusListViewModel
) {
    val scope = rememberCoroutineScope()
    val credentialPacks by credentialPacksViewModel.credentialPacks.collectAsState()
    val loadingCredentialPacks by credentialPacksViewModel.loading.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(credentialPacks) {
        if (credentialPacks.isNotEmpty()) {
            statusListViewModel.getStatusLists(credentialPacks)
        }
    }

    fun goTo(credentialPack: CredentialPack) {
        navController.navigate(
            Screen.CredentialDetailsScreen.route.replace(
                "{credential_pack_id}",
                credentialPack.id().toString()
            )
        )
    }

    fun onDelete(credentialPack: CredentialPack) {
        scope.launch {
            credentialPacksViewModel.deleteCredentialPack(credentialPack)
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
        }
    }

    fun onExport(credentialTitle: String, credentialPack: CredentialPack) {
        helpersViewModel.exportText(
            getFileContent(credentialPack),
            "$credentialTitle.json",
            "text/plain"
        )
    }

    if (!loadingCredentialPacks) {
        if (credentialPacks.isNotEmpty()) {
            SwipeRefresh(
                state = rememberSwipeRefreshState(isRefreshing),
                onRefresh = {
                    isRefreshing = true
                    scope.launch {
                        if (credentialPacks.isNotEmpty()) {
                            statusListViewModel.getStatusLists(credentialPacks)
                        }
                        isRefreshing = false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 20.dp)
                ) {
                    credentialPacks.forEach { credentialPack ->
                        val credentialItem = credentialDisplaySelector(
                            credentialPack = credentialPack,
                            statusListViewModel = statusListViewModel,
                            goTo = {
                                goTo(credentialPack)
                            },
                            onDelete = {
                                onDelete(credentialPack)
                            },
                            onExport = { credentialTitle ->
                                onExport(credentialTitle, credentialPack)
                            }
                        )
                        credentialItem.credentialPreviewAndDetails()
                    }
                    //        item {
                    //            ShareableCredentialListItems(mdocBase64 = mdocBase64)
                    //        }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.empty_wallet),
                        contentDescription = stringResource(id = R.string.empty_wallet),
                    )
                }
            }
        }
    } else {
        LoadingView(
            loadingText = ""
        )
    }
}
