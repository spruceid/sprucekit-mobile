package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone700

@Composable
fun ApplySpruceMdlConfirmation(
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(all = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Submitted successfully",
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal,
                color = ColorBlue600
            )

            Column(
                Modifier.height(100.dp)
            ) {
                HacApplicationListItem(
                    application = null,
                    startIssuance = { _, _ -> },
                    hacApplicationsViewModel = null
                )
            }


            Text(
                text = "Your information has been submitted. Approval can take between 20 minutes and 5 days.",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = ColorStone600,
                modifier = Modifier.padding(top = 12.dp)
            )

            Text(
                text = "After being approved, your credential will be show a valid status and be available to use.",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = ColorStone600,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 30.dp)
                .height(48.dp),
            shape = RoundedCornerShape(100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ColorStone700
            )
        ) {
            Text(
                text = "Okay, sounds good",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = ColorBase50
            )
        }
    }
} 