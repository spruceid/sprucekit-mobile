package com.spruceid.mobilesdkexample.verifiersettings

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
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.SettingsHomeItem
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Composable
fun VerifierSettingsHomeView(
    navController: NavController,
    verificationMethodsViewModel: VerificationMethodsViewModel
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        VerifierSettingsHomeHeader(
            onBack = {
                navController.navigate(
                    Screen.HomeScreen.route.replace("{tab}", "verifier")
                ) {
                    popUpTo(0)
                }
            }
        )
        VerifierSettingsHomeBody(
            navController = navController,
            verificationMethodsViewModel = verificationMethodsViewModel,
        )
    }
}

@Composable
fun VerifierSettingsHomeHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Settings",
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
                painter = painterResource(id = R.drawable.cog),
                contentDescription = stringResource(id = R.string.cog),
                colorFilter = ColorFilter.tint(ColorStone50),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
        }
    }
}

@Composable
fun VerifierSettingsHomeBody(
    navController: NavController,
    verificationMethodsViewModel: VerificationMethodsViewModel,
) {
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
            description = "View and export verification history.",
            action = {
                navController.navigate(Screen.VerifierSettingsActivityLogScreen.route)
            }
        )
        SettingsHomeItem(
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.unknown),
                    contentDescription = stringResource(id = R.string.trusted_certificates),
                    modifier = Modifier.padding(end = 5.dp),
                )
            },
            name = "Trusted Certificates",
            description = "Manage trusted certificates used during mDoc verification.",
            action = {
                navController.navigate(Screen.VerifierSettingsTrustedCertificatesScreen.route)
            }
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = {
                GlobalScope.launch {
                    verificationMethodsViewModel.deleteAllVerificationMethods()
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
                text = "Delete all added verification methods",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}
