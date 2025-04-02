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
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorEmerald600
import com.spruceid.mobilesdkexample.ui.theme.ColorRose700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone100
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.ColorYellow700
import com.spruceid.mobilesdkexample.ui.theme.Inter

@Composable
fun CredentialStatusSmall(status: CredentialStatusList) {
    when (status) {
        CredentialStatusList.VALID -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.valid),
                contentDescription = stringResource(id = R.string.valid_check),
                colorFilter = ColorFilter.tint(ColorEmerald600),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Valid",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorEmerald600
            )
        }

        CredentialStatusList.REVOKED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.invalid),
                contentDescription = stringResource(id = R.string.revoked_check),
                colorFilter = ColorFilter.tint(ColorRose700),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Revoked",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorRose700
            )
        }

        CredentialStatusList.SUSPENDED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.suspended),
                contentDescription = stringResource(id = R.string.suspended_check),
                colorFilter = ColorFilter.tint(ColorYellow700),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Suspended",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorYellow700
            )
        }

        CredentialStatusList.UNKNOWN -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.unknown),
                contentDescription = stringResource(id = R.string.unknown_check),
                colorFilter = ColorFilter.tint(ColorStone950),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Unknown",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorStone950
            )
        }

        CredentialStatusList.INVALID -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.invalid),
                contentDescription = stringResource(id = R.string.invalid_check),
                colorFilter = ColorFilter.tint(ColorRose700),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Invalid",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorRose700
            )
        }

        CredentialStatusList.UNDEFINED -> {}
        CredentialStatusList.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.pending_check),
                contentDescription = stringResource(id = R.string.pending_check),
                colorFilter = ColorFilter.tint(ColorBlue600),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Pending",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorBlue600
            )
        }

        CredentialStatusList.READY -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.valid),
                contentDescription = stringResource(id = R.string.ready_check),
                colorFilter = ColorFilter.tint(ColorEmerald600),
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp)
                    .padding(end = 3.dp)
            )
            Text(
                text = "Ready",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                color = ColorEmerald600
            )
        }
    }
}

@Composable
fun CredentialStatus(status: CredentialStatusList) {
    when (status) {
        CredentialStatusList.VALID -> Column {
            Text(
                "Status",
                fontFamily = Inter,
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
                    contentDescription = stringResource(id = R.string.valid_check),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "VALID",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }

        CredentialStatusList.REVOKED -> Column {
            Text(
                "Status",
                fontFamily = Inter,
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
                    contentDescription = stringResource(id = R.string.revoked_check),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "REVOKED",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }

        CredentialStatusList.SUSPENDED -> Column {
            Text(
                "Status",
                fontFamily = Inter,
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
                    .background(ColorYellow700)
                    .border(
                        width = 1.dp,
                        color = ColorYellow700,
                        shape = RoundedCornerShape(6.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.suspended),
                    contentDescription = stringResource(id = R.string.suspended_check),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "SUSPENDED",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }

        CredentialStatusList.UNKNOWN -> Column {
            Text(
                "Status",
                fontFamily = Inter,
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
                    contentDescription = stringResource(id = R.string.unknown_check),
                    colorFilter = ColorFilter.tint(ColorStone950),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "UNKNOWN",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorStone950
                )
            }
        }

        CredentialStatusList.INVALID -> Column {
            Text(
                "Status",
                fontFamily = Inter,
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
                    contentDescription = stringResource(id = R.string.invalid_check),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "INVALID",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }

        CredentialStatusList.UNDEFINED -> {}
        
        CredentialStatusList.PENDING -> Column {
            Text(
                "Status",
                fontFamily = Inter,
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
                    contentDescription = stringResource(id = R.string.pending_check),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "PENDING",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }

        CredentialStatusList.READY -> Column {
            Text(
                "Status",
                fontFamily = Inter,
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
                    contentDescription = stringResource(id = R.string.ready_check),
                    colorFilter = ColorFilter.tint(ColorBase50),
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .padding(end = 3.dp)
                )
                Text(
                    text = "READY",
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = ColorBase50
                )
            }
        }
    }
}