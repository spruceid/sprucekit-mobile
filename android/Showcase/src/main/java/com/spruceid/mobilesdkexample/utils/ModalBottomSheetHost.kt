package com.spruceid.mobilesdkexample.utils

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1

object ModalBottomSheetHost {
    private var sheetOpen by mutableStateOf(false)
    private var content: (@Composable () -> Unit)? by mutableStateOf(null)

    fun show(content: @Composable () -> Unit) {
        ModalBottomSheetHost.content = content
        sheetOpen = true
    }

    fun hide() {
        sheetOpen = false
        content = null
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Host() {
        if (sheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { hide() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                modifier = Modifier.navigationBarsPadding(),
                dragHandle = null,
                containerColor = ColorBase1,
                shape = RoundedCornerShape(16.dp)
            ) {
                content?.invoke()
            }
        }
    }
} 