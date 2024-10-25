package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.rs.MDocItem

@Composable
fun VerifierMDocResultView(
    navController: NavController,
    result: Map<String, Map<String, MDocItem>>
) {
    Column {
        Text(
            "Mobile Driver's License",
            color = Color(12, 10, 9, 1),
            fontSize = 32.dp,
            lineHeight = 40.dp,
            fontWeight = 500
        )
    }
}
