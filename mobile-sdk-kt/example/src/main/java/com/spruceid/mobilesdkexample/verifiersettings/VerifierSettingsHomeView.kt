package com.spruceid.mobilesdkexample.verifiersettings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.TextBody
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import com.spruceid.mobilesdkexample.ui.theme.VerifiedRedInvalid
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

enum class VerifierSubSettings {
    VERIFICATION_ACTIVITY_LOG,
}

@Composable
fun VerifierSettingsHomeView(
    navController: NavController,
    verificationMethodsViewModel: VerificationMethodsViewModel
) {

    var subpage by remember {
        mutableStateOf<VerifierSubSettings?>(null)
    }

    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        VerifierSettingsHomeHeader(
            onBack = {
                if (subpage != null) {
                    subpage = null
                } else {
                    navController.navigate(
                        Screen.HomeScreen.route.replace("{tab}", "verifier")
                    ) {
                        popUpTo(0)
                    }
                }
            }
        )
        VerifierSettingsHomeBody(
            subpage = subpage,
            verificationMethodsViewModel = verificationMethodsViewModel,
            changeSubPage = { sp ->
                subpage = sp
            }
        )
    }
}

@Composable
fun VerifierSettingsHomeHeader(
    onBack: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable {
            onBack()
        }
    ) {
        Image(
            painter = painterResource(id = R.drawable.chevron),
            contentDescription = stringResource(id = R.string.chevron),
            modifier = Modifier
                .rotate(180f)
                .scale(0.7f)
        )
        Text(
            text = "Verifier Settings",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 24.sp,
            color = TextHeader,
            modifier = Modifier.padding(start = 10.dp)
        )
        Spacer(Modifier.weight(1f))
    }

}

@Composable
fun VerifierSettingsHomeBody(
    subpage: VerifierSubSettings?,
    verificationMethodsViewModel: VerificationMethodsViewModel,
    changeSubPage: (VerifierSubSettings?) -> Unit
) {
    if (subpage == null) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        changeSubPage(VerifierSubSettings.VERIFICATION_ACTIVITY_LOG)
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
                                Icons.AutoMirrored.Outlined.List,
                                contentDescription = stringResource(id = R.string.verification_activity_log),
                                modifier = Modifier.padding(end = 5.dp),
                            )
                            Text(
                                text = "Verification Activity Log",
                                fontFamily = Inter,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = TextBody,
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
                        text = "view and export verification history",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = TextBody,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    GlobalScope.launch {
                        verificationMethodsViewModel.deleteAllVerificationMethods()
                    }
                },
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VerifiedRedInvalid,
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
    } else if (subpage == VerifierSubSettings.VERIFICATION_ACTIVITY_LOG) {
        VerificationActivityLogsScreen()
    }
}
