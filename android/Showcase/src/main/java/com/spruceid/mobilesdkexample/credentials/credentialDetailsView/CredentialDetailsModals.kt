package com.spruceid.mobilesdkexample.credentials.credentialDetailsView

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.ICredentialView
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.viewmodels.HelpersViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel
import com.spruceid.mobilesdkexample.walletsettings.WalletSettingsActivityLogScreenBody

@Composable
fun DetailsModal(
    credentialItem: ICredentialView?,
    statusList: CredentialStatusList?,
    onClose: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
    ) {
        // Header with drag handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(
                        ColorStone600,
                        RoundedCornerShape(2.dp)
                    )
                    .align(Alignment.Center)
            )
        }

        // Content
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
        ) {
            credentialItem?.let {
                if (statusList != CredentialStatusList.REVOKED) {
                    credentialItem.credentialDetails()
                } else {
                    credentialItem.credentialRevokedInfo {
                        onClose()
                        onBack()
                    }
                }
            }
        }

        // Bottom close button bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        indication = LocalIndication.current,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        onClose()
                    }
                    .padding(vertical = 8.dp, horizontal = 16.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.invalid_check),
                    contentDescription = "Close",
                    colorFilter = ColorFilter.tint(Color.Black),
                    modifier = Modifier
                        .scale(0.75f)
                        .rotate(180f)
                )
                Text(
                    text = "Close",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ActivityLogModal(
    credentialPackId: String,
    onClose: () -> Unit
) {
    val walletActivityLogsViewModel: WalletActivityLogsViewModel = activityHiltViewModel()
    val helpersViewModel: HelpersViewModel = activityHiltViewModel()
    var activityLogs by remember { mutableStateOf(listOf<WalletActivityLogs>()) }

    LaunchedEffect(credentialPackId) {
        activityLogs =
            walletActivityLogsViewModel.getWalletActivityLogsByCredentialPackId(credentialPackId)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
    ) {
        // Header with drag handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(
                        ColorStone600,
                        RoundedCornerShape(2.dp)
                    )
                    .align(Alignment.Center)
            )
        }

        // Title
        Text(
            text = "Activity Log",
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            color = ColorStone950,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        // Content
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp)
        ) {
            WalletSettingsActivityLogScreenBody(
                walletActivityLogs = activityLogs,
                export = { logs ->
                    val credentialIdSuffix = credentialPackId.takeLast(8)
                    helpersViewModel.exportText(
                        walletActivityLogsViewModel.generateWalletActivityLogCSV(logs = logs),
                        "activity_logs_${credentialIdSuffix}.csv",
                        "text/csv"
                    )
                }
            )
        }

        // Bottom close button bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        indication = LocalIndication.current,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        onClose()
                    }
                    .padding(vertical = 8.dp, horizontal = 16.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.invalid_check),
                    contentDescription = "Close",
                    colorFilter = ColorFilter.tint(Color.Black),
                    modifier = Modifier
                        .scale(0.75f)
                        .rotate(180f)
                )
                Text(
                    text = "Close",
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = ColorStone950,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
