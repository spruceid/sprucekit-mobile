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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald900
import com.spruceid.mobilesdkexample.ui.theme.ColorRose700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme

@Composable
fun VerifierBinarySuccessView(
    success: Boolean,
    description: String,
    onClose: () -> Unit
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
                    .background(ColorEmerald900)
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
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .width(30.dp)
                                .height(30.dp),
                            colorFilter = ColorFilter.tint(Color.White)
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
                    .background(ColorRose700)
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
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .width(30.dp)
                                .height(30.dp),
                            colorFilter = ColorFilter.tint(Color.White)
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
            color = ColorStone950,
            modifier = Modifier.padding(top = 12.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onClose,
            shape = RoundedCornerShape(5.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ColorStone700,
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
    MobileSdkTheme {
        VerifierBinarySuccessView(
            success = true,
            description = "Valid"
        ) {}
    }
}