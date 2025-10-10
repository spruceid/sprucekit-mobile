package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.LoadingView
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.components.HeaderButton
import com.spruceid.mobilesdkexample.ui.components.HomeHeader
import com.spruceid.mobilesdkexample.ui.theme.ColorAmber600
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.utils.getFileContent
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import com.spruceid.mobilesdkexample.walletsettings.generateMockMdl
import kotlinx.coroutines.launch

@Composable
fun WalletHomeView(
    navController: NavController
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier.fillMaxSize()
        ) {
            WalletHomeHeader(navController = navController)
            Column(
                Modifier.padding(horizontal = 20.dp)
            ) {
                WalletHomeBody(
                    navController = navController
                )
            }

        }
    }
}

@Composable
fun WalletHomeHeader(
    navController: NavController?
) {
    val gradientColors = listOf(ColorBlue600, ColorBase1)
    val buttons = listOf(
        HeaderButton(
            icon = painterResource(id = R.drawable.qrcode_scanner),
            contentDescription = stringResource(id = R.string.qrcode_scanner),
            onClick = { navController?.navigate(Screen.ScanQRScreen.route) }
        ),
        HeaderButton(
            icon = painterResource(id = R.drawable.user),
            contentDescription = stringResource(id = R.string.user),
            onClick = { navController?.navigate(Screen.WalletSettingsHomeScreen.route) }
        )
    )

    HomeHeader(
        title = "Wallet",
        gradientColors = gradientColors,
        buttons = buttons
    )
}

@Composable
fun WalletHomeBody(
    navController: NavController
) {
    val credentialPacksViewModel: CredentialPacksViewModel = activityHiltViewModel()
    val walletActivityLogsViewModel: WalletActivityLogsViewModel = activityHiltViewModel()
    val statusListViewModel: StatusListViewModel = activityHiltViewModel()
    val helpersViewModel: HelpersViewModel = activityHiltViewModel()
    val hacApplicationsViewModel: HacApplicationsViewModel = activityHiltViewModel()

    val scope = rememberCoroutineScope()
    val credentialPacks by credentialPacksViewModel.credentialPacks.collectAsState()
    val loadingCredentialPacks by credentialPacksViewModel.loading.collectAsState()
    val hacApplications by hacApplicationsViewModel.hacApplications.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(credentialPacks) {
        if (credentialPacks.isNotEmpty()) {
            statusListViewModel.getStatusLists(credentialPacks)
        }
        hacApplicationsViewModel.updateAllIssuanceStates()
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
        if (credentialPacks.isNotEmpty() || hacApplications.isNotEmpty()) {
            SwipeRefresh(
                state = rememberSwipeRefreshState(isRefreshing),
                onRefresh = {
                    isRefreshing = true
                    scope.launch {
                        if (credentialPacks.isNotEmpty()) {
                            statusListViewModel.getStatusLists(credentialPacks)
                        }
                        hacApplicationsViewModel.updateAllIssuanceStates()
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
                    hacApplications.forEach { hacApplication ->
                        HacApplicationListItem(
                            application = hacApplication,
                            startIssuance = { url, callback ->
                                navController.navigate(
                                    Screen.HandleOID4VCI.route
                                        .replace(
                                            "{url}",
                                            url.replace("openid-credential-offer://", "")
                                        )
                                )
                                navController.currentBackStackEntry?.savedStateHandle?.set(
                                    "callback",
                                    callback
                                )
                            },
                            hacApplicationsViewModel = hacApplicationsViewModel,
                        )
                    }
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
                        credentialItem.CredentialPreviewAndDetails()
                    }
                    //        item {
                    //            ShareableCredentialListItems(mdocBase64 = mdocBase64)
                    //        }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(30.dp))
                    WalletHomeViewNoCredentials(onGenerateMockMdl = {
                        isRefreshing = true
                        scope.launch {
                            generateMockMdl(credentialPacksViewModel, walletActivityLogsViewModel)
                        }
                        isRefreshing = false
                    })
                }
            }
        }
    } else {
        LoadingView(
            loadingText = ""
        )
    }
}