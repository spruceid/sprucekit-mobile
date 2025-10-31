package com.spruceid.mobilesdkexample.credentials

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.rs.FlowState
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone100
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Switzer

@Composable
fun ApplicationStatusSmall(status: FlowState) {
    when (status) {
        is FlowState.ProofingRequired -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.unknown),
                contentDescription = stringResource(id = R.string.application_proofing_required),
                colorFilter = ColorFilter.tint(ColorStone950),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Proofing Required",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorStone950
            )
        }

        FlowState.AwaitingManualReview -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.pending_check),
                contentDescription = stringResource(id = R.string.application_awaiting_manual_review),
                colorFilter = ColorFilter.tint(ColorBlue600),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Awaiting Manual Review",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorBlue600
            )
        }

        is FlowState.ReadyToProvision -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.valid),
                contentDescription = stringResource(id = R.string.application_ready_to_provision),
                colorFilter = ColorFilter.tint(ColorEmerald600),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Ready to Provision",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorEmerald600
            )
        }

        FlowState.ApplicationDenied -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.invalid),
                contentDescription = stringResource(id = R.string.application_denied),
                colorFilter = ColorFilter.tint(ColorRose700),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Application Denied",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorRose700
            )
        }
    }
}

@Composable
fun ApplicationStatus(status: FlowState) {
    when (status) {
        is FlowState.ProofingRequired -> Column {
            Text(
                "Status",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = ColorStone500,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ColorStone100)
                    .border(
                        width = 1.dp,
                        color = ColorStone300,
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.unknown),
                    contentDescription = stringResource(id = R.string.application_proofing_required),
                    colorFilter = ColorFilter.tint(ColorStone950),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "PROOFING REQUIRED",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorStone950
                )
            }
        }

        FlowState.AwaitingManualReview -> Column {
            Text(
                "Status",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = ColorStone500,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ColorBlue600)
                    .border(
                        width = 1.dp,
                        color = ColorBlue600,
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.pending_check),
                    contentDescription = stringResource(id = R.string.application_awaiting_manual_review),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "AWAITING MANUAL REVIEW",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }

        is FlowState.ReadyToProvision -> Column {
            Text(
                "Status",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = ColorStone500,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ColorEmerald600)
                    .border(
                        width = 1.dp,
                        color = ColorEmerald600,
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.valid),
                    contentDescription = stringResource(id = R.string.application_ready_to_provision),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "READY TO PROVISION",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }

        FlowState.ApplicationDenied -> Column {
            Text(
                "Status",
                fontFamily = Switzer,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                color = ColorStone500,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ColorRose700)
                    .border(
                        width = 1.dp,
                        color = ColorRose700,
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.invalid),
                    contentDescription = stringResource(id = R.string.application_denied),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "APPLICATION DENIED",
                    fontFamily = Switzer,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }
    }
}