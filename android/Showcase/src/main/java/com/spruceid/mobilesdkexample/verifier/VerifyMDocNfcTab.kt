package com.spruceid.mobilesdkexample.verifier

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.nfc.NfcReaderPhase
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter

/**
 * Pure UI for the NFC verifier tab. Engagement lifecycle (reader mode,
 * APDU exchange, NFC adapter state) is owned by the parent [VerifyMDocView]
 * via [com.spruceid.mobile.sdk.nfc.rememberNfcReaderEngagement].
 */
@Composable
internal fun VerifyMDocNfcTab(
    phase: NfcReaderPhase,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (phase) {
                    NfcReaderPhase.Unsupported -> {
                        Text(
                            text = "This device does not support NFC.",
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            color = ColorStone950,
                            textAlign = TextAlign.Center,
                        )
                    }
                    NfcReaderPhase.Disabled -> {
                        Text(
                            text = "NFC is turned off. Enable NFC in system settings to continue.",
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            color = ColorStone950,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ColorBlue600),
                            shape = RoundedCornerShape(5.dp),
                        ) {
                            Text(
                                "Open NFC Settings",
                                fontFamily = Inter,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
                    }
                    NfcReaderPhase.WaitingForTag -> {
                        Text(
                            text = "Tap the holder's phone to share their credential.",
                            fontFamily = Inter,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = ColorStone950,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Hold the phones back-to-back until the share completes.",
                            fontFamily = Inter,
                            fontSize = 14.sp,
                            color = ColorStone500,
                            textAlign = TextAlign.Center,
                        )
                    }
                    NfcReaderPhase.Exchanging -> {
                        CircularProgressIndicator(color = ColorBlue600)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Negotiating handover…",
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            color = ColorStone500,
                        )
                    }
                    is NfcReaderPhase.ProtocolError -> {
                        Text(
                            text = phase.cause.localizedMessage
                                ?: phase.cause.message
                                ?: "Handover failed",
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            color = Color.Red,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Tap again to retry.",
                            fontFamily = Inter,
                            fontSize = 14.sp,
                            color = ColorStone500,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.Black,
            ),
            border = BorderStroke(1.dp, ColorStone300),
            shape = RoundedCornerShape(5.dp),
        ) {
            Text(
                "Cancel",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black,
            )
        }
    }
}
