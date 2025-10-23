package com.spruceid.mobilesdkexample.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.spruceid.mobilesdkexample.HomeView
import com.spruceid.mobilesdkexample.credentials.AddToWalletView
import com.spruceid.mobilesdkexample.credentials.credentialDetailsView.CredentialDetailsView
import com.spruceid.mobilesdkexample.verifier.AddVerificationMethodView
import com.spruceid.mobilesdkexample.verifier.VerifyCwtView
import com.spruceid.mobilesdkexample.verifier.VerifyDLView
import com.spruceid.mobilesdkexample.verifier.VerifyDelegatedOid4vpView
import com.spruceid.mobilesdkexample.verifier.VerifyEAView
import com.spruceid.mobilesdkexample.verifier.VerifyMDocView
import com.spruceid.mobilesdkexample.verifier.VerifyVCView
import com.spruceid.mobilesdkexample.verifier.VerifyVcbVdlView
import com.spruceid.mobilesdkexample.verifiersettings.VerifierSettingsActivityLogScreen
import com.spruceid.mobilesdkexample.verifiersettings.VerifierSettingsHomeView
import com.spruceid.mobilesdkexample.verifiersettings.VerifierSettingsTrustedCertificatesView
import com.spruceid.mobilesdkexample.wallet.DispatchQRView
import com.spruceid.mobilesdkexample.wallet.HandleMdocOID4VPView
import com.spruceid.mobilesdkexample.wallet.HandleOID4VCIView
import com.spruceid.mobilesdkexample.wallet.HandleOID4VPView
import com.spruceid.mobilesdkexample.walletsettings.WalletSettingsActivityLogScreen
import com.spruceid.mobilesdkexample.walletsettings.WalletSettingsHomeView

@Composable
fun SetupNavGraph(
    navController: NavHostController
) {
    NavHost(navController = navController, startDestination = Screen.HomeScreen.route) {
        composable(
            route = Screen.HomeScreen.route,
            arguments = listOf(
                navArgument("tab") {
                    type = NavType.StringType; defaultValue = "wallet"
                }
            ),
        ) { backStackEntry ->
            val tab = backStackEntry.arguments?.getString("tab")!!
            HomeView(
                navController,
                initialTab = tab
            )
        }
        composable(
            route = Screen.VerifyDLScreen.route,
        ) {
            VerifyDLView(navController)
        }
        composable(
            route = Screen.VerifyEAScreen.route,
        ) {
            VerifyEAView(navController)
        }
        composable(
            route = Screen.VerifyVCScreen.route,
        ) {
            VerifyVCView(navController)
        }
        composable(
            route = Screen.VerifyCWTScreen.route,
        ) {
            VerifyCwtView(navController)
        }
        composable(
            route = Screen.VerifyMDocScreen.route,
        ) {
            VerifyMDocView(navController)
        }
        composable(
            route = Screen.VerifyVcbVdlScreen.route,
        ) {
            VerifyVcbVdlView(navController)
        }
        composable(
            route = Screen.VerifyMDlOver18Screen.route,
        ) {
            VerifyMDocView(
                navController,
                checkAgeOver18 = true
            )
        }
        composable(
            route = Screen.VerifyDelegatedOid4vpScreen.route,
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")!!
            VerifyDelegatedOid4vpView(
                navController,
                verificationId = id
            )
        }
        composable(
            route = Screen.VerifierSettingsHomeScreen.route,
        ) {
            VerifierSettingsHomeView(navController)
        }
        composable(
            route = Screen.VerifierSettingsActivityLogScreen.route,
        ) {
            VerifierSettingsActivityLogScreen(navController)
        }
        composable(
            route = Screen.VerifierSettingsTrustedCertificatesScreen.route,
        ) {
            VerifierSettingsTrustedCertificatesView(navController)
        }
        composable(
            route = Screen.AddVerificationMethodScreen.route,
        ) {
            AddVerificationMethodView(navController)
        }
        composable(
            route = Screen.WalletSettingsHomeScreen.route,
        ) {
            WalletSettingsHomeView(navController)
        }
        composable(
            route = Screen.WalletSettingsActivityLogScreen.route,
        ) {
            WalletSettingsActivityLogScreen(navController)
        }
        composable(
            route = Screen.AddToWalletScreen.route,
            deepLinks =
                listOf(navDeepLink {
                    uriPattern = "spruceid://?sd-jwt={rawCredential}"
                })
        ) { backStackEntry ->
            val rawCredential = backStackEntry.arguments?.getString("rawCredential")

            // Check if is a valid sd-jwt
            if (!rawCredential.isNullOrEmpty()) {
                AddToWalletView(
                    navController,
                    rawCredential
                )
            } else {
                navController.navigate(Screen.HomeScreen.route) {
                    popUpTo(Screen.HomeScreen.route) { inclusive = true }
                }
            }
        }
        composable(
            route = Screen.ScanQRScreen.route,
        ) { DispatchQRView(navController) }
        composable(
            route = Screen.HandleOID4VCI.route,
        ) { backStackEntry ->
            val url = backStackEntry.arguments?.getString("url")!!
            HandleOID4VCIView(
                navController,
                url
            )
        }
        composable(
            route = Screen.HandleOID4VP.route,
            deepLinks = listOf(navDeepLink { uriPattern = "openid4vp://{url}" }),
            arguments = listOf(
                navArgument("url") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            var url = backStackEntry.arguments?.getString("url")!!
            if (!url.startsWith("openid4vp")) {
                url = "openid4vp://$url"
            }
            Box(modifier = Modifier.padding(top = 48.dp)) {
                HandleOID4VPView(
                    navController,
                    url,
                    null,
                )
            }
        }
        composable(
            route = Screen.HandleOID4VPWithCredentialPack.route,
            arguments = listOf(
                navArgument("url") {
                    type = NavType.StringType
                },
                navArgument("credential_pack_id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            var url = backStackEntry.arguments?.getString("url")!!
            if (!url.startsWith("openid4vp")) {
                url = "openid4vp://$url"
            }
            val credentialPackId = backStackEntry.arguments?.getString("credential_pack_id")
            Box(modifier = Modifier.padding(top = 48.dp)) {
                HandleOID4VPView(
                    navController,
                    url,
                    credentialPackId,
                )
            }
        }
        composable(
            route = Screen.HandleMdocOID4VP.route,
            deepLinks = listOf(navDeepLink { uriPattern = "mdoc-openid4vp://{url}" }),
            arguments = listOf(
                navArgument("url") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            var url = backStackEntry.arguments?.getString("url")!!
            if (!url.startsWith("mdoc-openid4vp")) {
                url = "mdoc-openid4vp://$url"
            }
            Box(modifier = Modifier.padding(top = 48.dp)) {
                HandleMdocOID4VPView(
                    navController,
                    url,
                    null
                )
            }
        }
        composable(
            route = Screen.HandleMdocOID4VPWithCredentialPack.route,
            arguments = listOf(
                navArgument("url") {
                    type = NavType.StringType
                },
                navArgument("credential_pack_id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            var url = backStackEntry.arguments?.getString("url")!!
            if (!url.startsWith("mdoc-openid4vp")) {
                url = "mdoc-openid4vp://$url"
            }
            val credentialPackId = backStackEntry.arguments?.getString("credential_pack_id")
            Box(modifier = Modifier.padding(top = 48.dp)) {
                HandleMdocOID4VPView(
                    navController,
                    url,
                    credentialPackId
                )
            }
        }
        composable(
            route = Screen.CredentialDetailsScreen.route
        ) { backStackEntry ->
            val credentialPackId = backStackEntry.arguments?.getString("credential_pack_id")!!
            CredentialDetailsView(
                navController,
                credentialPackId
            )
        }
    }
}
