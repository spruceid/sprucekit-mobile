package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
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
        modifier = Modifier.navigationBarsPadding()
    ) {
        Text(
            text = "Credential Options",
            textAlign = TextAlign.Center,
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            color = ColorStone950,
            modifier = Modifier
                .fillMaxWidth()
        )
        if (onExport != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = {
                    setShowBottomSheet(false)
                    onExport()
                },
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorBlue600,
                ),
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Export",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    color = ColorBlue600,
                )
            }
        }
        if (onDelete != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = {
                    setShowBottomSheet(false)
                    onDelete()
                },
                shape = RoundedCornerShape(5.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = ColorRose600,
                ),
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Delete",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    color = ColorRose600,
                )
            }
        }

        Button(
            onClick = {
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) {
                        setShowBottomSheet(false)
                    }
                }
            },
            shape = RoundedCornerShape(5.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = ColorBlue600,
            ),
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Text(
                text = "Cancel",
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                color = ColorBlue600,
            )
        }
    }
}