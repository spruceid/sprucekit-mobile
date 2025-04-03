package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobilesdkexample.credentials.CredentialStatusSmall
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel

@Composable
fun HacApplicationListItem(
    application: HacApplications?,
    startIssuance: (String, suspend () -> Unit) -> Unit,
    hacApplicationsViewModel: HacApplicationsViewModel?
) {
    var credentialOfferUrl by remember { mutableStateOf<String?>(null) }
    var credentialStatus by remember { mutableStateOf(CredentialStatusList.UNDEFINED) }

    LaunchedEffect(application) {
        if (application != null) {
            try {
                val status = hacApplicationsViewModel?.issuanceClient?.checkStatus(
                    issuanceId = application.issuanceId,
                    walletAttestation = hacApplicationsViewModel.getWalletAttestation()!!
                )
                if (status?.state == "ReadyToProvision") {
                    credentialStatus = CredentialStatusList.READY
                }
                credentialOfferUrl = status?.openidCredentialOffer
            } catch (e: Exception) {
                println(e.message)
            }
        } else {
            credentialStatus = CredentialStatusList.PENDING
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .border(
                width = 1.dp,
                color = ColorBase300,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
            .clickable(
                enabled = credentialOfferUrl != null,
                onClick = {
                    credentialOfferUrl?.let { url ->
                        startIssuance(url) {
                            application?.let {
                                hacApplicationsViewModel?.deleteApplication(application.id)
                            }
                        }
                    }
                }
            )
    ) {
        Column {
            Text(
                text = "Mobile Drivers License",
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                color = ColorStone950,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            CredentialStatusSmall(status = credentialStatus)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

