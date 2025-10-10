package com.spruceid.mobilesdkexample.credentials.credentialDetailsView

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.ui.theme.ColorBase50
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue200
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone100
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950

@Composable
fun CredentialDetailFooter(
    selectedTab: CredentialMode,
    hasShareSupport: Boolean,
    onScanClick: () -> Unit,
    onShareClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onActivityLogClick: () -> Unit,
    onMoreClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorBase50)
    ) {
        ScanShareButtons(
            selectedTab = selectedTab,
            hasShareSupport = hasShareSupport,
            onScanClick = onScanClick,
            onShareClick = onShareClick,
        )

        MiddleMenuSection(
            onDetailsClick = onDetailsClick,
            onActivityLogClick = onActivityLogClick,
            onMoreClick = onMoreClick
        )

        CloseButtonSection(
            onCloseClick = onCloseClick
        )
    }
}

@Composable
private fun ScanShareButtons(
    selectedTab: CredentialMode,
    hasShareSupport: Boolean,
    onScanClick: () -> Unit,
    onShareClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .width(300.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.25f)
                )
                .background(
                    ColorBase1,
                    RoundedCornerShape(24.dp)
                )
                .border(
                    width = 1.dp,
                    color = ColorStone200,
                    shape = RoundedCornerShape(24.dp)
                )
                .clip(RoundedCornerShape(24.dp))
                .padding(4.dp)
        ) {
            // Scan button
            ModeButton(
                isSelected = selectedTab == CredentialMode.SCAN,
                isEnabled = true,
                iconRes = R.drawable.qrcode_scanner,
                text = "Scan",
                onClick = onScanClick
            )

            // Share button - disabled if no mdoc support
            ModeButton(
                isSelected = selectedTab == CredentialMode.SHARE,
                isEnabled = hasShareSupport,
                iconRes = R.drawable.qrcode,
                text = "Share",
                onClick = onShareClick
            )
        }
    }
}

@Composable
private fun RowScope.ModeButton(
    isSelected: Boolean,
    isEnabled: Boolean,
    iconRes: Int,
    text: String,
    onClick: () -> Unit
) {
    val textColor = when {
        !isEnabled -> ColorStone600.copy(alpha = 0.4f)
        isSelected -> Color.Black
        else -> ColorStone600
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .then(
                if (isSelected && isEnabled) Modifier
                    .background(
                        ColorBlue200,
                        RoundedCornerShape(22.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = ColorBlue300,
                        shape = RoundedCornerShape(22.dp)
                    )
                else Modifier
            )
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                enabled = isEnabled,
                indication = LocalIndication.current,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onClick()
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = text,
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp)
                    .padding(end = 8.dp),
                colorFilter = ColorFilter.tint(textColor)
            )
            Text(
                text = text,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = textColor
            )
        }
    }
}

@Composable
private fun MiddleMenuSection(
    onDetailsClick: () -> Unit,
    onActivityLogClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 20.dp, end = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = Color.Black.copy(alpha = 0.15f),
                    spotColor = Color.Black.copy(alpha = 0.15f)
                )
                .background(
                    ColorBase1,
                    RoundedCornerShape(12.dp)
                )
                .border(
                    width = 1.dp,
                    color = ColorStone200,
                    shape = RoundedCornerShape(12.dp)
                )
                .clip(RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            MenuRow(
                iconRes = R.drawable.info_icon,
                text = "Details",
                onClick = onDetailsClick
            )

            HorizontalDivider(
                thickness = 1.dp,
                color = ColorStone100
            )

            MenuRow(
                iconRes = R.drawable.verification_activity_log,
                text = "Activity Log",
                onClick = onActivityLogClick
            )

            HorizontalDivider(
                thickness = 1.dp,
                color = ColorStone100
            )

            MenuRow(
                iconRes = R.drawable.three_dots_horizontal,
                text = "More",
                onClick = onMoreClick
            )
        }
    }
}

@Composable
private fun MenuRow(
    iconRes: Int,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(
                indication = LocalIndication.current,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                onClick()
            }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = text,
                modifier = Modifier
                    .width(28.dp)
                    .height(28.dp)
                    .padding(end = 12.dp),
                colorFilter = ColorFilter.tint(ColorStone950)
            )
            Text(
                text = text,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = ColorStone950
            )
        }
        Image(
            painter = painterResource(id = R.drawable.chevron),
            contentDescription = "Arrow",
            modifier = Modifier
                .width(24.dp)
                .height(24.dp),
            colorFilter = ColorFilter.tint(Color.Black)
        )
    }
}

@Composable
private fun CloseButtonSection(
    onCloseClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 16.dp, bottom = 20.dp),
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
                    onCloseClick()
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
