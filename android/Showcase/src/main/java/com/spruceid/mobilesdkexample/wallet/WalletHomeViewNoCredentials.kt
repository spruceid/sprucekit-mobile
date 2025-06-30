package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorBase100
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue100
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue800
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600

@Composable
fun WalletHomeViewNoCredentials (
    onGenerateMockMdl: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    val isSmallWidth = screenWidth < 380.dp
    val isSmallHeight = screenHeight < 640.dp

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
                    fontSize = if (isSmallWidth) {
                        22.sp
                    } else {
                        24.sp
                    },
                    fontWeight = FontWeight.W600,
                    color = ColorBlue600
                )
                Text(
                    text = "You currently have no credentials in your wallet",
                    fontSize = if (isSmallWidth) {
                        12.sp
                    } else {
                        14.sp
                    },
                    fontWeight = FontWeight.W500,
                    color = ColorStone600
                )
            }

            // Image (mDL)
            Image(
                painter = painterResource(id = R.drawable.mdl_image),
                contentDescription = "mDL Image",
                contentScale = ContentScale.Fit,
                modifier = if (isSmallHeight) {
                    Modifier
                        .height(160.dp)   // smaller height for small screens
                        .fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                }
            )

            // Button
            Box(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .border(
                        width = 2.dp,
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = 0.2f),
                                0.4f to ColorBlue800,
                                1.0f to ColorBlue900
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        ),
                        shape = RoundedCornerShape(100.dp)
                    )
                    .clip(RoundedCornerShape(100.dp))
            ) {
                Button(
                    onClick = onGenerateMockMdl,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ColorBlue600,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(100.dp),
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.generate_mdl),
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "Generate a Spruce mDL",
                        fontSize = if (isSmallWidth) {
                            14.sp
                        } else {
                            16.sp
                        },
                        fontWeight = FontWeight.W400
                    )
                }
            }
        }
    }
}