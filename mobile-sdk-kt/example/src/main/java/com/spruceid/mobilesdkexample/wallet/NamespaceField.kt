package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme

@Composable
fun NamespaceField(
    namespace: Map.Entry<String, Boolean>,
    isChecked: Boolean,
    onCheck: (Boolean) -> Unit
) {
    MobileSdkTheme {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = namespace.key,
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
            )
            Checkbox(
                isChecked,
                onCheckedChange = onCheck
            )
        }
    }
}
