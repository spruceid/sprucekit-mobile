package com.spruceid.mobilesdkexample.wallet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
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
import com.spruceid.mobilesdkexample.ui.theme.ColorBase100
import com.spruceid.mobilesdkexample.ui.theme.ColorBase150
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue100
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
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
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    statusListViewModel: StatusListViewModel,
    helpersViewModel: HelpersViewModel,
    hacApplicationsViewModel: HacApplicationsViewModel
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
            statusListViewModel = statusListViewModel,
            hacApplicationsViewModel = hacApplicationsViewModel
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
    statusListViewModel: StatusListViewModel,
    hacApplicationsViewModel: HacApplicationsViewModel
) {
    val scope = rememberCoroutineScope()
    val credentialPacks by credentialPacksViewModel.credentialPacks.collectAsState()
    val loadingCredentialPacks by credentialPacksViewModel.loading.collectAsState()
    val hacApplications by hacApplicationsViewModel.hacApplications.collectAsState()
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
        if (credentialPacks.isNotEmpty() || hacApplications.isNotEmpty()) {
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
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(30.dp))
                    NoCredentialCard(onGenerateMockMdl = {
                        isRefreshing = true
                        scope.launch {
                            generateMockMdl(credentialPacksViewModel)
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
@Composable
fun NoCredentialCard(
    onGenerateMockMdl: () -> Unit
) {
    AnimatedVisibility (
        visible = true,
        enter = EnterTransition.None,
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                // Shadow is not quite accurate
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = Color.Black.copy(alpha = 0.6f),
                    spotColor = Color.Black.copy(alpha = 0.6f),
                )
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(ColorBase100, ColorBlue100),
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 2.dp,
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 16.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title + Subtitle
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Welcome!",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.W600,
                        color = ColorBlue600
                    )
                    Text(
                        text = "You currently have no credentials in your wallet",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W500,
                        color = ColorStone600
                    )
                }

                // Image (mDL)
                Image(
                    painter = painterResource(id = R.drawable.mdl_image),
                    contentDescription = "mDL Image"
                )

                // Button
                Button(
                    onClick = onGenerateMockMdl,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ColorBlue600,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(100.dp),
                    modifier = Modifier
                        .height(55.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.generate_mdl),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Generate a Spruce mDL",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.W400
                    )
                }
            }
        }
    }
}
