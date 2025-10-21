package com.spruceid.mobilesdkexample.credentials.credentialDetailsView

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobilesdkexample.credentials.CredentialStatusSmall
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel
import java.util.UUID

@Composable
fun CompactCredentialInfo(
    credentialPack: CredentialPack?
) {
    credentialPack?.let { pack ->
        val credentialInfo = getCredentialIdTitleAndIssuer(pack)
        val name = credentialInfo.second
        val issuer = credentialInfo.third ?: "Unknown Issuer"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    ColorBase50,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = name,
                fontFamily = Switzer,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = ColorStone950
            )

            val statusListViewModel: StatusListViewModel = activityHiltViewModel()
            val statusList by statusListViewModel.observeStatusForId(
                UUID.fromString(
                    credentialPack?.id().toString()
                )
            )
                .collectAsState()

            val displayStatus =
                if (statusList == null || statusList == CredentialStatusList.UNDEFINED) {
                    CredentialStatusList.VALID
                } else {
                    statusList!!
                }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = issuer,
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = ColorStone600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                CredentialStatusSmall(displayStatus)
            }
        }
    }
}

