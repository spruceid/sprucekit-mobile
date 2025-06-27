package com.spruceid.mobilesdkexample.wallet

import android.content.Intent
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.spruceid.mobile.sdk.rs.FlowState
import com.spruceid.mobilesdkexample.credentials.ApplicationStatusSmall
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.ui.theme.ColorBase300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.ControlledSimpleDeleteAlertDialog
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel
import kotlinx.coroutines.launch

@Composable
fun HacApplicationListItem(
    application: HacApplications?,
    startIssuance: (String, suspend () -> Unit) -> Unit,
    hacApplicationsViewModel: HacApplicationsViewModel?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var issuanceStatus by remember { mutableStateOf<FlowState?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(application) {
        if (application != null) {
            try {
                issuanceStatus = hacApplicationsViewModel?.issuanceClient?.checkStatus(
                    issuanceId = application.issuanceId,
                    walletAttestation = hacApplicationsViewModel.getWalletAttestation()!!
                )
            } catch (e: Exception) {
                println(e.message)
            }
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
                enabled = issuanceStatus != FlowState.AwaitingManualReview,
                onClick = {
                    issuanceStatus?.let {
                        when (it) {
                            is FlowState.ProofingRequired -> {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    it.proofingUrl.toUri()
                                )
                                context.startActivity(intent)
                            }

                            is FlowState.ReadyToProvision -> {
                                startIssuance(it.openidCredentialOffer) {
                                    application?.let {
                                        hacApplicationsViewModel?.deleteApplication(application.id)
                                    }
                                }
                            }

                            FlowState.ApplicationDenied -> {
                                showDeleteDialog = true
                            }

                            else -> {}
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
            issuanceStatus?.let {
                ApplicationStatusSmall(status = it)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }

    ControlledSimpleDeleteAlertDialog(
        showDialog = showDeleteDialog,
        message = "Are you sure you want to delete this application? This action cannot be undone.",
        onConfirm = {
            scope.launch {
                application?.let {
                    hacApplicationsViewModel?.deleteApplication(application.id)
                    showDeleteDialog = false
                }
            }
        },
        onClose = {
            showDeleteDialog = false
        },
        confirmButtonText = "Delete"
    )
}

