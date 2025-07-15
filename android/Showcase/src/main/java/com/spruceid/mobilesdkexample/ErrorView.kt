package com.spruceid.mobilesdkexample

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorView(
    errorTitle: String,
    errorDetails: String,
    closeButtonLabel: String = "Close",
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by remember {
        mutableStateOf(false)
    }

    Box(
        Modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.error),
                contentDescription = stringResource(id = R.string.error)
            )
            Text(
                errorTitle,
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
                color = ColorRose600,
                textAlign = TextAlign.Center
            )
            Text(
                "View technical details",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = ColorStone600,
                textAlign = TextAlign.Center,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    showSheet = true
                }
            )
        }

        Column {
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    onClose()
                },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorStone950,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(30.dp)
                    .navigationBarsPadding()
                    .border(
                        width = 1.dp,
                        color = ColorStone300,
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                Text(
                    text = closeButtonLabel,
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    color = ColorStone950,
                )
            }
        }

        if (showSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showSheet = false
                },
                sheetState = sheetState,
                dragHandle = null,
                containerColor = Color.Transparent,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((LocalConfiguration.current.screenHeightDp * .8f).dp)
                        .background(Color.White),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(top = 48.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .weight(weight = 1f, fill = false)
                                .background(ColorStone50)
                                .border(1.dp, ColorStone300, RoundedCornerShape(6.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = errorDetails,
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Black,
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .navigationBarsPadding(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    showSheet = false
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = ColorStone950,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        width = 1.dp,
                                        color = ColorStone300,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .weight(1f)
                            ) {
                                Text(
                                    text = "Close",
                                    fontFamily = Inter,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ColorStone950,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}