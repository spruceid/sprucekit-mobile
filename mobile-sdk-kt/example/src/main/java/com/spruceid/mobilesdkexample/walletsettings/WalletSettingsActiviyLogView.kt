package com.spruceid.mobilesdkexample.walletsettings

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone400
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.formatSqlDateTime
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel

@Composable
fun WalletSettingsActivityLogScreen(
    navController: NavController,
    walletActivityLogsViewModel: WalletActivityLogsViewModel,
    helpersViewModel: HelpersViewModel
) {
    val walletActivityLogs by walletActivityLogsViewModel.walletActivityLogs.collectAsState()

    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
    ) {
        WalletSettingsActivityLogScreenHeader(
            onBack = {
                navController.popBackStack()
            }
        )
        WalletSettingsActivityLogScreenBody(
            walletActivityLogs = walletActivityLogs,
            export = { logs ->
                helpersViewModel.exportText(
                    walletActivityLogsViewModel.generateWalletActivityLogCSV(logs = logs),
                    "wallet_activity_logs.csv",
                    "text/csv"
                )
            }
        )
    }
}

@Composable
fun WalletSettingsActivityLogScreenHeader(onBack: () -> Unit) {
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
    }
}

@Composable
fun WalletSettingsActivityLogScreenBody(
    walletActivityLogs: List<WalletActivityLogs>,
    export: (List<WalletActivityLogs>) -> Unit
) {
    Column(
        Modifier
            .padding(top = 10.dp)
            .navigationBarsPadding(),
    ) {
        if (walletActivityLogs.isEmpty()) {
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
                items(walletActivityLogs) { log ->
                    Column {
                        Text(
                            text = log.credentialTitle,
                            fontFamily = Inter,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            color = ColorStone950,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Text(
                            text = log.action,
                            fontFamily = Inter,
                            fontWeight = FontWeight.Normal,
                            fontSize = 15.sp,
                            color = ColorStone600,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Text(
                            text = formatSqlDateTime(log.dateTime),
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
                    export(walletActivityLogs)
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