package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.components.HeaderButton
import com.spruceid.mobilesdkexample.ui.components.HomeHeader
import com.spruceid.mobilesdkexample.ui.theme.ColorAmber600
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorPurple600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.ColorTerracotta600
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationMethodsViewModel

@Composable
fun VerifierHomeView(
    navController: NavController
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier.fillMaxSize()
        ) {
            VerifierHomeHeader(navController = navController)
            Column(
                Modifier.padding(horizontal = 26.dp)
            ) {
                VerifierHomeBody(
                    navController = navController
                )
            }
        }
    }
}

@Composable
fun VerifierHomeHeader(
    navController: NavController,
) {
    val gradientColors = listOf(ColorAmber600, ColorBase1)
    val buttons = listOf(
        HeaderButton(
            icon = painterResource(id = R.drawable.add),
            contentDescription = stringResource(id = R.string.add),
            onClick = { navController.navigate(Screen.AddVerificationMethodScreen.route) }
        ),
        HeaderButton(
            icon = painterResource(id = R.drawable.cog),
            contentDescription = stringResource(id = R.string.cog),
            onClick = { navController.navigate(Screen.VerifierSettingsHomeScreen.route) }
        )
    )

    HomeHeader(
        title = "Verifier",
        gradientColors = gradientColors,
        buttons = buttons
    )
}

@Composable
fun VerifierHomeBody(
    navController: NavController
) {
    val verificationMethodsViewModel: VerificationMethodsViewModel = activityHiltViewModel()
    val verificationMethods = remember { verificationMethodsViewModel.verificationMethods }

    fun getBadgeType(verificationType: String): VerifierListItemTagType {
        if (verificationType == "DelegatedVerification") {
            return VerifierListItemTagType.DISPLAY_QR_CODE
        } else {
            return VerifierListItemTagType.SCAN_QR_CODE
        }
    }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .padding(bottom = 90.dp)
    ) {

        item {
            VerifierListItem(
                title = "Mobile Driver's License",
                description = "Verifies an ISO formatted mobile driver's license by reading a QR code",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyMDocScreen.route)
                }
            )
            VerifierListItem(
                title = "Mobile Driver's License - Over 18",
                description = "Verifies an ISO formatted mobile driver's license by reading a QR code",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyMDlOver18Screen.route)
                }
            )
            VerifierListItem(
                title = "Verify VCB VDL",
                description = "Verify a driver's license encoded as a verifiable credential QRCode",
                type = VerifierListItemTagType.SCAN_QR_CODE,
                modifier = Modifier.clickable {
                    navController.navigate(Screen.VerifyVcbVdlScreen.route)
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
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontFamily = Switzer,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = ColorStone950,
                modifier = Modifier.weight(4f)
            )
            Spacer(modifier = Modifier.weight(1f))
            VerifierListItemTag(type = type)
        }
        Text(
            text = description,
            fontFamily = Switzer,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = ColorStone600,
        )
    }
    HorizontalDivider(color = ColorStone200)
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
                    fontFamily = Switzer,
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
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.padding(start = 6.dp, end = 2.dp)
                )
                Image(
                    painter = painterResource(id = R.drawable.arrow_triangle_right),
                    contentDescription = stringResource(id = R.string.arrow_triangle_right),
                )
            }
        }
    }
}
