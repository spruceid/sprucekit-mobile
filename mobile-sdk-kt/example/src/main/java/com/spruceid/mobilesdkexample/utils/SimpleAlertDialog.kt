package com.spruceid.mobilesdkexample.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun SimpleAlertDialog(
    trigger: @Composable () -> Unit,
    message: String?
) {
    var showDialog by remember { mutableStateOf(false) }

    Column(Modifier.clickable {
        if (message != null) {
            showDialog = true
        }
    }) {
        trigger()
    }


    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Close")
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(message ?: "")
                }
            }
        )
    }
}