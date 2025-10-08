package com.spruceid.mobilesdkexample.wallet.wallethomeview

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.components.HeaderButton
import com.spruceid.mobilesdkexample.ui.components.HomeHeader
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue500

@Composable
fun WalletHomeHeader(
    navController: NavController?,
) {
    val gradientColors = listOf(ColorBlue500, ColorBase1)

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