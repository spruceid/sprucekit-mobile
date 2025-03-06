package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorBase150
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorPurple600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.ColorTerracotta600
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel

@Composable
fun VerifierHomeView(
    navController: NavController,
    verificationMethodsViewModel: VerificationMethodsViewModel
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        VerifierHomeHeader(navController = navController)
        VerifierHomeBody(
            navController = navController,
            verificationMethodsViewModel = verificationMethodsViewModel
        )
    }
}

@Composable
fun VerifierHomeHeader(
    navController: NavController
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Verifier",
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
                .background(ColorBase150)
                .clickable {
                    navController.navigate(Screen.VerifierSettingsHomeScreen.route)
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.cog),
                contentDescription = stringResource(id = R.string.cog),
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
            )
        }
    }
}

@Composable
fun VerifierHomeBody(
    navController: NavController,
    verificationMethodsViewModel: VerificationMethodsViewModel
) {
    val verificationMethods = remember { verificationMethodsViewModel.verificationMethods }

    fun getBadgeType(verificationType: String): VerifierListItemTagType {
        if (verificationType == "DelegatedVerification") {
            return VerifierListItemTagType.DISPLAY_QR_CODE
        } else {
            return VerifierListItemTagType.SCAN_QR_CODE
        }
    }

    Row(
        modifier = Modifier
            .padding(top = 20.dp)
    ) {
        Text(
            text = "VERIFICATIONS",
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = ColorStone400
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "+ New Verification",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = ColorBlue600,
            modifier = Modifier.clickable {
                navController.navigate(Screen.AddVerificationMethodScreen.route)
            }
        )
    }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(bottom = 60.dp)
    ) {

        item {
            VerifierListItem(
                title = "Driver's License Document",
                description = "Verifies physical driver's licenses issued by the state of Utopia",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyDLScreen.route)
                }
            )
            VerifierListItem(
                title = "Employment Authorization Document",
                description = "Verifies physical Employment Authorization issued by the state of Utopia",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyEAScreen.route)
                }
            )
            VerifierListItem(
                title = "Mobile Driver's Licence",
                description = "Verifies an ISO formatted mobile driver's license by reading a QR code",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyMDocScreen.route)
                }
            )
            VerifierListItem(
                title = "Mobile Driver's Licence - Over 18",
                description = "Verifies an ISO formatted mobile driver's license by reading a QR code",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyMDlOver18Screen.route)
                }
            )
            VerifierListItem(
                title = "Verifiable Credential",
                description = "Verifies a verifiable credential by reading the verifiable presentation QR code",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyVCScreen.route)
                }
            )
            VerifierListItem(
                title = "CWT",
                description = "Verifies a CWT by reading a QR code",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyCWTScreen.route)
                }
            )
        }
        items(verificationMethods.value) { verificationMethod ->
            VerifierListItem(
                title = verificationMethod.name,
                description = verificationMethod.description,
                type = getBadgeType(verificationMethod.type),
                modifier = Modifier.clickable {
                    navController.navigate(
                        Screen.VerifyDelegatedOid4vpScreen.route.replace(
                            "{id}",
                            verificationMethod.id.toString()
                        )
                    )
                }
            )
        }
    }
}

enum class VerifierListItemTagType {
    DISPLAY_QR_CODE, SCAN_QR_CODE
}

@Composable
fun VerifierListItem(
    title: String,
    description: String,
    type: VerifierListItemTagType,
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
                color = ColorStone950,
                modifier = Modifier.weight(4f)
            )
            Spacer(modifier = Modifier.weight(1f))
            VerifierListItemTag(type = type)
        }
        Text(
            text = description,
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = ColorStone600,
        )
    }
    HorizontalDivider()
}

@Composable
fun VerifierListItemTag(
    type: VerifierListItemTagType
) {
    when (type) {
        VerifierListItemTagType.DISPLAY_QR_CODE -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(shape = RoundedCornerShape(100.dp))
                    .background(ColorPurple600)
                    .padding(vertical = 2.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.qrcode),
                    contentDescription = stringResource(id = R.string.arrow_triangle_right),
                )
                Text(
                    text = "Display",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
                Image(
                    painter = painterResource(id = R.drawable.arrow_triangle_right),
                    contentDescription = stringResource(id = R.string.arrow_triangle_right),
                )
            }

        }

        VerifierListItemTagType.SCAN_QR_CODE -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(shape = RoundedCornerShape(100.dp))
                    .background(ColorTerracotta600)
                    .padding(vertical = 2.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.qrcode_scanner),
                    contentDescription = stringResource(id = R.string.arrow_triangle_right),
                )
                Text(
                    text = "Scan",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
                Image(
                    painter = painterResource(id = R.drawable.arrow_triangle_right),
                    contentDescription = stringResource(id = R.string.arrow_triangle_right),
                )
            }
        }
    }
}