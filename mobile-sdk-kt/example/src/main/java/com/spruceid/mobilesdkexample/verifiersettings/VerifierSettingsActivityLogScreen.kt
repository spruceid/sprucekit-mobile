package com.spruceid.mobilesdkexample.verifiersettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.spruceid.mobile.sdk.credentialStatusListFromString
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.CredentialStatusSmall
import com.spruceid.mobilesdkexample.db.VerificationActivityLogs
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone900
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.MobileSdkTheme
import com.spruceid.mobilesdkexample.utils.DropdownInput
import com.spruceid.mobilesdkexample.utils.formatSqlDateTime
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.VerificationActivityLogsViewModel

@Composable
fun VerifierSettingsActivityLogScreen(
    navController: NavController,
    verificationActivityLogsViewModel: VerificationActivityLogsViewModel,
    helpersViewModel: HelpersViewModel
) {
    // TODO: WIP: we will finish these filters in the future
    // val distinctCredentialTitles = verificationActivityLogsViewModel.getDistinctCredentialTitles()
    val verificationActivityLogs by verificationActivityLogsViewModel.verificationActivityLogs.collectAsState()

    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        VerifierSettingsActivityLogScreenHeader(
            onBack = {
                navController.popBackStack()
            }
        )
        VerifierSettingsActivityLogScreenBody(
            verificationActivityLogs = verificationActivityLogs,
            export = { logs ->
                helpersViewModel.exportText(
                    verificationActivityLogsViewModel.generateVerificationActivityLogCSV(logs = logs),
                    "activity_logs.csv",
                    "text/csv"
                )
            }
        )
        // TODO: WIP: we will finish these filters in the future
        // FilterModal()
    }
}

@Composable
fun VerifierSettingsActivityLogScreenHeader(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(36.dp)
            .clickable {
                onBack()
            }
    ) {
        Image(
            painter = painterResource(id = R.drawable.chevron),
            contentDescription = stringResource(id = R.string.chevron),
            modifier = Modifier
                .rotate(180f)
                .scale(0.4f)
        )
        Text(
            text = "Activity Log",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = ColorStone950
        )
        Spacer(Modifier.weight(1f))

        // TODO: WIP: we will finish these filters in the future
        // val distinctCredentialTitles = verificationActivityLogsViewModel.getDistinctCredentialTitles()
//        Box(
//            contentAlignment = Alignment.Center,
//            modifier = Modifier
//                .width(36.dp)
//                .height(36.dp)
//                .padding(start = 4.dp)
//                .shadow(
//                    elevation = 10.dp,
//                    spotColor = ColorStone950,
//                    shape = RoundedCornerShape(6.dp)
//                )
//                .border(
//                    width = 1.dp,
//                    color = ColorBase100,
//                    shape = RoundedCornerShape(6.dp)
//                )
//                .background(ColorBase1)
//                .clickable {
//
//                }
//        ) {
//            Image(
//                painter = painterResource(id = R.drawable.filter),
//                contentDescription = stringResource(id = R.string.filter),
//                modifier = Modifier
//                    .width(20.dp)
//                    .height(20.dp)
//            )
//        }
    }
}

@Composable
fun VerifierSettingsActivityLogScreenBody(
    verificationActivityLogs: List<VerificationActivityLogs>,
    export: (List<VerificationActivityLogs>) -> Unit
) {
    Column(
        Modifier
            .padding(top = 10.dp)
            .navigationBarsPadding(),
    ) {
        if (verificationActivityLogs.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No Activity Log Found",
                    fontFamily = Inter,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    color = ColorStone400
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxSize()
                    .weight(weight = 1f, fill = false),
            ) {
                items(verificationActivityLogs) { log ->
                    Column {
                        Text(
                            text = log.credentialTitle,
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            color = ColorStone950,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        if (log.issuer.isNotBlank()) {
                            Text(
                                text = log.issuer,
                                fontFamily = Inter,
                                fontWeight = FontWeight.Normal,
                                fontSize = 15.sp,
                                color = ColorStone600,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        CredentialStatusSmall(status = credentialStatusListFromString(log.status))
                        Text(
                            text = formatSqlDateTime(log.verificationDateTime),
                            fontFamily = Inter,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp,
                            color = ColorStone600,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    }
                }
            }
            Button(
                onClick = {
                    export(verificationActivityLogs)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = ColorStone300,
                        shape = RoundedCornerShape(100.dp)
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorBase1,
                    contentColor = ColorStone950,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.export),
                        contentDescription = stringResource(id = R.string.export),
                        modifier = Modifier.padding(end = 5.dp),
                    )
                    Text(
                        text = "Export",
                        fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = ColorStone950,
                    )
                }
            }
        }
    }
}

// TODO: WIP: We will finish these filters in the future
@Composable
fun FilterModal() {
    Dialog(onDismissRequest = { }) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = ColorBase1,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Title
                Column(
                    Modifier
                        .padding(horizontal = 24.dp)
                        .padding(vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Filters",
                            fontFamily = Inter,
                            fontWeight = FontWeight.Normal,
                            fontSize = 24.sp,
                            color = ColorStone900
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.clickable { }
                        )
                    }
                }

                // Title divider
                HorizontalDivider()

                // Body
                Column(
                    Modifier
                        .padding(horizontal = 24.dp)
                        .padding(vertical = 12.dp)
                ) {
                    DropdownInput(
                        options = listOf("Option 1", "Option 2", "Option 3"),
                        onSelect = { option ->

                        }
                    )
                }

                // Footer divider
                HorizontalDivider()

                Row(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .padding(vertical = 12.dp)
                ) {
                    Button(
                        onClick = {

                        },
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.Black,
                        ),
                        border = BorderStroke(1.dp, ColorStone300),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(end = 6.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black,
                        )
                    }
                    Button(
                        onClick = {

                        },
                        shape = RoundedCornerShape(100.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorStone700,
                            contentColor = ColorBase50,
                        ),
                        border = BorderStroke(1.dp, ColorStone700),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 6.dp)
                    ) {
                        Text(
                            text = "Apply",
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            color = ColorBase50,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FilterModalPreview() {
    MobileSdkTheme {
        FilterModal()
    }
}