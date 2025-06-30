package com.spruceid.mobilesdkexample.utils

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose600
import com.spruceid.mobilesdkexample.ui.theme.Inter

@Composable
fun ControlledSimpleDeleteAlertDialog(
    showDialog: Boolean,
    message: String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    confirmButtonText: String = "Confirm",
    dismissButtonText: String = "Cancel"
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { onClose() },
            confirmButton = {
                TextButton(onClick = { onConfirm() }) {
                    Text(
                        text = confirmButtonText,
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        color = ColorRose600,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onClose() }) {
                    Text(
                        text = dismissButtonText,
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        color = ColorBlue600,
                    )
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(message)
                }
            }
        )
    }
}