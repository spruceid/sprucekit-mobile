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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.GenericCredentialItem
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.Primary
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel

@Composable
fun WalletHomeView(
    navController: NavController,
    credentialPacksViewModel: CredentialPacksViewModel
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)) {
        WalletHomeHeader(navController = navController)
        WalletHomeBody(credentialPacksViewModel = credentialPacksViewModel)
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
            color = TextHeader
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
                .background(Primary)
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
                .background(Primary)
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
fun WalletHomeBody(credentialPacksViewModel: CredentialPacksViewModel) {
    val credentialPacks by credentialPacksViewModel.credentialPacks.collectAsState()

    if (credentialPacks.isNotEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 20.dp)
            ) {
                credentialPacks.forEach { credentialPack ->
                    GenericCredentialItem(
                        credentialPack = credentialPack,
                        onDelete = {
                            credentialPacksViewModel.deleteCredentialPack(credentialPack)
                        }
                    )
                    .credentialPreviewAndDetails()
                }
                //        item {
                //            ShareableCredentialListItems(mdocBase64 = mdocBase64)
                //        }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.drawable.add_first_credential),
                    contentDescription = stringResource(id = R.string.add_first_credential),
                )
            }
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
}
