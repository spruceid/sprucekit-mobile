package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose300
import com.spruceid.mobilesdkexample.ui.theme.ColorRose500
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose800
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialOptionsDialogActions(
    setShowBottomSheet: (Boolean) -> Unit,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = {
            setShowBottomSheet(false)
        },
        sheetState = sheetState,
        modifier = Modifier.navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = null,
        containerColor = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            // Action buttons section grouped together
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ColorBase50)
            ) {
                // Title
                Text(
                    text = "Credential Options",
                    textAlign = TextAlign.Center,
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = ColorStone950.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                )

                if (onExport != null) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = ColorStone950.copy(alpha = 0.2f)
                    )
                    Button(
                        onClick = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    setShowBottomSheet(false)
                                }
                            }
                            onExport()
                        },
                        shape = RoundedCornerShape(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorBase50,
                            contentColor = ColorBlue600,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Export",
                            fontFamily = Switzer,
                            fontWeight = FontWeight.Normal,
                            fontSize = 18.sp,
                            color = ColorBlue600,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                if (onDelete != null) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = ColorStone950.copy(alpha = 0.2f)
                    )
                    Button(
                        onClick = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    setShowBottomSheet(false)
                                }
                            }
                            onDelete()
                        },
                        shape = RoundedCornerShape(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorBase50,
                            contentColor = ColorRose600,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Delete",
                            fontFamily = Switzer,
                            fontWeight = FontWeight.Normal,
                            fontSize = 18.sp,
                            color = ColorRose600,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            // Spacer between actions and cancel
            Spacer(modifier = Modifier.padding(4.dp))

            // Cancel button (completely separated like iOS)
            Button(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            setShowBottomSheet(false)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorBase50,
                    contentColor = ColorBlue600,
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Cancel",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    color = ColorBlue600,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // Bottom padding for safe area
            Spacer(modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}