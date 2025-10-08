package com.spruceid.mobilesdkexample.wallet

import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.spruceid.mobile.sdk.rs.FlowState
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.credentials.ApplicationStatusSmall
import com.spruceid.mobilesdkexample.db.HacApplications
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.ControlledSimpleDeleteAlertDialog
import com.spruceid.mobilesdkexample.viewmodels.HacApplicationsViewModel
import kotlinx.coroutines.launch

@Composable
fun HacApplicationListItem(
    application: HacApplications,
    startIssuance: (String, suspend () -> Unit) -> Unit,
    hacApplicationsViewModel: HacApplicationsViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val issuanceStates by hacApplicationsViewModel.issuanceStates.collectAsState()
    val issuanceStatus = issuanceStates[application.id]

    LaunchedEffect(application.id) {
        if (issuanceStates[application.id] == null) {
            hacApplicationsViewModel.updateIssuanceState(application.id, application.issuanceId)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 15.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(16.dp),
                    ambientColor = Color.Black.copy(alpha = 0.3f),
                    spotColor = Color.Black.copy(alpha = 0.3f),
                )
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = 1.dp,
                    shape = RoundedCornerShape(16.dp),
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFC8BFAD),
                            0.3f to Color.White.copy(alpha = 0.2f),
                            0.8f to Color.White.copy(alpha = 0.2f),
                            1.0f to Color(0xFFC8BFAD),
                        ),
                    )
                )
                .clickable(
                    enabled = issuanceStatus != FlowState.AwaitingManualReview,
                    onClick = {
                        issuanceStatus?.let { status ->
                            when (status) {
                                is FlowState.ProofingRequired -> {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        status.proofingUrl.toUri()
                                    )
                                    context.startActivity(intent)
                                }

                                is FlowState.ReadyToProvision -> {
                                    startIssuance(status.openidCredentialOffer) {
                                        hacApplicationsViewModel.deleteApplication(application.id)
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
            // Background image
            Image(
                painter = painterResource(id = R.drawable.credential_bg),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.6f),
                contentScale = ContentScale.Crop
            )

            // Semi-transparent white overlay to show "pending" state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.75f))
            )

            // Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top row: Logo and status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // SpruceID Logo
                    Image(
                        painter = painterResource(id = R.drawable.spruce_logo),
                        contentDescription = "SpruceID Logo",
                        modifier = Modifier.size(24.dp)
                    )

                    // Status badge or loading indicator
                    if (issuanceStatus == null) {
                        // Show loading indicator with shimmer effect
                        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 0.9f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha"
                        )

                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .alpha(alpha),
                            strokeWidth = 2.dp,
                            color = ColorStone950.copy(alpha = 0.6f)
                        )
                    } else {
                        ApplicationStatusSmall(status = issuanceStatus)
                    }
                }

                // Push content to bottom
                Spacer(modifier = Modifier.weight(1f))

                // Credential title
                Text(
                    text = "Mobile Drivers License",
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    color = ColorStone950,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.1f),
                            offset = Offset(1f, 1f),
                            blurRadius = 2f
                        )
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle
                Text(
                    text = "Credential Application",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    color = ColorStone950.copy(alpha = 0.7f)
                )
            }
        }
    }

    ControlledSimpleDeleteAlertDialog(
        showDialog = showDeleteDialog,
        message = "Are you sure you want to delete this application? This action cannot be undone.",
        onConfirm = {
            scope.launch {
                hacApplicationsViewModel.deleteApplication(application.id)
                showDeleteDialog = false
            }
        },
        onClose = {
            showDeleteDialog = false
        },
        confirmButtonText = "Delete"
    )
}

