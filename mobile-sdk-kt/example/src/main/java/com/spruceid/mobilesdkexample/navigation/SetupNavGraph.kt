package com.spruceid.mobilesdkexample.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.spruceid.mobilesdkexample.HomeView
import com.spruceid.mobilesdkexample.credentials.AddToWalletView
import com.spruceid.mobilesdkexample.verifier.AddVerificationMethodView
import com.spruceid.mobilesdkexample.verifier.VerifyDLView
import com.spruceid.mobilesdkexample.verifier.VerifyDelegatedOid4vpView
import com.spruceid.mobilesdkexample.verifier.VerifyEAView
import com.spruceid.mobilesdkexample.verifier.VerifyMDocView
import com.spruceid.mobilesdkexample.verifier.VerifyVCView
import com.spruceid.mobilesdkexample.verifiersettings.VerifierSettingsHomeView
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import com.spruceid.mobilesdkexample.wallet.DispatchQRView
import com.spruceid.mobilesdkexample.wallet.HandleOID4VPView
import com.spruceid.mobilesdkexample.wallet.OID4VCIView
import com.spruceid.mobilesdkexample.walletsettings.WalletSettingsHomeView

@Composable
fun SetupNavGraph(
    navController: NavHostController,
    verificationMethodsViewModel: VerificationMethodsViewModel
) {
    NavHost(navController = navController, startDestination = Screen.HomeScreen.route) {
        composable(
            route = Screen.HomeScreen.route,
        ) {
            HomeView(
                navController,
                verificationMethodsViewModel = verificationMethodsViewModel
            )
        }
        composable(
            route = Screen.VerifyDLScreen.route,
        ) { VerifyDLView(navController) }
        composable(
            route = Screen.VerifyEAScreen.route,
        ) { VerifyEAView(navController) }
        composable(
            route = Screen.VerifyVCScreen.route,
        ) { VerifyVCView(navController) }
        composable(
            route = Screen.VerifyMDocScreen.route,
        ) { VerifyMDocView(navController) }
        composable(
            route = Screen.VerifyDelegatedOid4vpScreen.route,
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")!!
            VerifyDelegatedOid4vpView(
                navController,
                verificationId = id,
                verificationMethodsViewModel
            )
        }
        composable(
            route = Screen.VerifierSettingsHomeScreen.route,
        ) {
            VerifierSettingsHomeView(
                navController,
                verificationMethodsViewModel = verificationMethodsViewModel
            )
        }
        composable(
            route = Screen.AddVerificationMethodScreen.route,
        ) {
            AddVerificationMethodView(
                navController,
                verificationMethodsViewModel = verificationMethodsViewModel
            )
        }
        composable(
            route = Screen.WalletSettingsHomeScreen.route,
        ) { WalletSettingsHomeView(navController) }
        composable(
            route = Screen.AddToWalletScreen.route,
            deepLinks =
            listOf(navDeepLink { uriPattern = "spruceid://?sd-jwt={rawCredential}" })
        ) { backStackEntry ->
            val rawCredential = backStackEntry.arguments?.getString("rawCredential")!!
            AddToWalletView(navController, rawCredential)
        }
        composable(
            route = Screen.ScanQRScreen.route,
        ) { DispatchQRView(navController) }
        composable(
            route = Screen.OID4VCIScreen.route,
        ) { OID4VCIView(navController) }
        composable(
            route = Screen.HandleOID4VP.route,
            deepLinks = listOf(navDeepLink { uriPattern = "openid4vp://{url}" })
        ) { backStackEntry ->
            var url = backStackEntry.arguments?.getString("url")!!
            if (!url.startsWith("openid4vp")) {
                url = "openid4vp://$url"
            }
            HandleOID4VPView(navController, url)
        }
    }
}
