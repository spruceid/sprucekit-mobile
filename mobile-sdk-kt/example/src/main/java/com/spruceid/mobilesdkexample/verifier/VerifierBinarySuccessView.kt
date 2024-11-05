package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import com.spruceid.mobilesdkexample.ui.theme.VerifiedGreenValid
import com.spruceid.mobilesdkexample.ui.theme.VerifiedRedInvalid
import com.spruceid.mobilesdkexample.ui.theme.VerifierCloseButton

@Composable
fun VerifierBinarySuccessView(
    navController: NavController,
    success: Boolean,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .padding(top = 40.dp)
    ) {
        if (success) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(shape = RoundedCornerShape(8.dp))
                    .background(VerifiedGreenValid)
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Spacer(modifier = Modifier.weight(1.0f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.valid_check),
                            contentDescription = stringResource(id = R.string.valid_check),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = "True",
                            fontFamily = Inter,
                            fontWeight = FontWeight.Normal,
                            fontSize = 32.sp,
                            color = Color.White,
                        )
                    }

                }
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(shape = RoundedCornerShape(8.dp))
                    .background(VerifiedRedInvalid)
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Spacer(modifier = Modifier.weight(1.0f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.invalid_check),
                            contentDescription = stringResource(id = R.string.invalid_check),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Text(
                            text = "False",
                            fontFamily = Inter,
                            fontWeight = FontWeight.Normal,
                            fontSize = 32.sp,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        Text(
            text = description,
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 26.sp,
            color = TextHeader,
            modifier = Modifier.padding(top = 12.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                navController.popBackStack()
            },
            shape = RoundedCornerShape(5.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VerifierCloseButton,
                contentColor = Color.White,
            ),
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Text(
                text = "Close",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VerifierBinarySuccessViewPreview() {
    val navController: NavHostController = rememberNavController()
    MobileSdkTheme {
        VerifierBinarySuccessView(
            navController = navController,
            success = true,
            description = "Valid"
        )
    }
}