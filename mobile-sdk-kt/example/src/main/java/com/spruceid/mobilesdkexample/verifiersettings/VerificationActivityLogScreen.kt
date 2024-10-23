package com.spruceid.mobilesdkexample.verifiersettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.ui.theme.CodeBorder
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.SpruceBlue
import com.spruceid.mobilesdkexample.ui.theme.TextBody
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VerificationActivityLogsScreen() {
//    val verificationActivityLogs by verificationActivityLogsViewModel.verificationActivityLogs.collectAsState()
    val verificationActivityLogs = listOf<VerificationActivityLogs>()
    val dateFormatter = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())

    LazyColumn(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp),
    ) {
        item {
            Text(
                text = "Coming Soon",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = TextHeader,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(verificationActivityLogs) { log ->
            Column {
                Text(
                    text = log.name,
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = TextHeader,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    text = log.credentialTitle,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = TextBody,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier =
                    Modifier
                        .fillMaxWidth(),
                ) {
                    Text(
                        text = log.status,
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = TextBody,
                        modifier =
                        Modifier
                            .padding(bottom = 4.dp),
                    )
                    Text(
                        text = "${if (log.expirationDate.before(
                                Date(),
                            )
                        ) {
                            "expired"
                        } else {
                            "expires"
                        }} on ${dateFormatter.format(log.expirationDate)}",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        color = TextBody,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    text = "Scanned on ${dateFormatter.format(log.date)}",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.End,
                    color = CodeBorder,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
            }
        }
        item {
            Button(
                onClick = {
//                    settingsViewModel.exportMetrics(logsViewModel.generateActivityLogCSV(), "activity_logs.csv")
                },
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = SpruceBlue,
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = "Export",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White,
                )
            }
        }
    }
}