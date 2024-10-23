package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.Primary
import com.spruceid.mobilesdkexample.ui.theme.TextBody
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import com.spruceid.mobilesdkexample.ui.theme.TextOnPrimary
import com.spruceid.mobilesdkexample.ui.theme.VerifierRequestBadgeBinaryBorder
import com.spruceid.mobilesdkexample.ui.theme.VerifierRequestBadgeBinaryFill
import com.spruceid.mobilesdkexample.ui.theme.VerifierRequestBadgeBinaryText
import com.spruceid.mobilesdkexample.ui.theme.VerifierRequestBadgeFieldBorder
import com.spruceid.mobilesdkexample.ui.theme.VerifierRequestBadgeFieldFill
import com.spruceid.mobilesdkexample.ui.theme.VerifierRequestBadgeFieldText

@Composable
fun VerifierHomeView(
    navController: NavController
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        VerifierHomeHeader(navController = navController)
        VerifierHomeBody(navController = navController)
    }
}

@Composable
fun VerifierHomeHeader(
    navController: NavController
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "SpruceKit Demo Verifier",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = TextHeader
        )
        Spacer(Modifier.weight(1f))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(36.dp)
                .height(36.dp)
                .padding(start = 4.dp)
                .clip(shape = RoundedCornerShape(8.dp))
                .background(Primary)
                .clickable {
                    navController.navigate(Screen.VerifierSettingsHomeScreen.route)
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
fun VerifierHomeBody(
    navController: NavController
) {
    Text(
        text = "REQUESTS",
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = TextOnPrimary,
        modifier = Modifier
            .padding(top = 20.dp)
    )
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
//        VerifierListItem(
//            title = "Driver's License Document",
//            description = "Verifies physical driver's licenses issued by the state of Utopia",
//            binary = true,
//            fields = 0,
//            modifier = Modifier.clickable {
//                navController.navigate(Screen.VerifyDLScreen.route)
//            }
//        )
//        VerifierListItem(
//            title = "Employment Authorization Document",
//            description = "Verifies physical Employment Authorization issued by the state of Utopia",
//            binary = true,
//            fields = 0,
//            modifier = Modifier.clickable {
//                navController.navigate(Screen.VerifyEAScreen.route)
//            }
//        )
        VerifierListItem(
            title = "Verifiable Credential",
            description = "Verifies a verifiable credential by reading the verifiable presentation QR code",
            binary = true,
            fields = 0,
            modifier = Modifier.clickable {
                navController.navigate(Screen.VerifyVCScreen.route)
            }
        )
    }
}

@Composable
fun VerifierListItem(
    title: String,
    description: String,
    binary: Boolean,
    fields: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = TextHeader,
                modifier = Modifier.weight(2f)
            )
//            VerifierListItemTag(binary = binary, fields = fields)
            Spacer(modifier = Modifier.weight(1f))
            Image(
                painter = painterResource(id = R.drawable.arrow_right),
                contentDescription = stringResource(id = R.string.arrow_right),
                modifier = Modifier.width(24.dp)
            )
        }
        Text(
            text = description,
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = TextBody,
        )
        HorizontalDivider()
    }
}

@Composable
fun VerifierListItemTag(
    binary: Boolean,
    fields: Int
) {
    if (binary) {
        Text(
            text = "Binary",
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = VerifierRequestBadgeBinaryText,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = VerifierRequestBadgeBinaryBorder,
                    shape = RoundedCornerShape(8.dp)
                )
                .clip(shape = RoundedCornerShape(8.dp, 8.dp, 8.dp, 8.dp))
                .background(VerifierRequestBadgeBinaryFill)
                .padding(vertical = 2.dp)
                .padding(horizontal = 8.dp),
        )
    } else {
        Text(
            text = "$fields Fields",
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = VerifierRequestBadgeFieldText,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = VerifierRequestBadgeFieldBorder,
                    shape = RoundedCornerShape(8.dp)
                )
                .clip(shape = RoundedCornerShape(8.dp, 8.dp, 8.dp, 8.dp))
                .background(VerifierRequestBadgeFieldFill)
                .padding(vertical = 2.dp)
                .padding(horizontal = 8.dp),
        )
    }
}