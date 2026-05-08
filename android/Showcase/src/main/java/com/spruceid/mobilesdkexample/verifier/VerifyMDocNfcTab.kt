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
import com.spruceid.mobilesdkexample.ui.theme.ColorBlue600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone500
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter

internal sealed class NfcTabUi {
    object NfcUnsupported : NfcTabUi()
    object NfcDisabled : NfcTabUi()
    object WaitingForTag : NfcTabUi()
    object Exchanging : NfcTabUi()
    data class ProtocolError(val message: String) : NfcTabUi()
}

/**
 * Pure UI for the NFC verifier tab. Engagement lifecycle (reader mode,
 * APDU exchange) is owned by the parent [VerifyMDocView] which lifts the
 * [NfcReaderEngagement] to the screen scope so reader mode can stay on
 * across all tabs and states.
 */
@Composable
internal fun VerifyMDocNfcTab(
    nfcUi: NfcTabUi,
    onRetry: () -> Unit,
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
                when (val s = nfcUi) {
                    is NfcTabUi.NfcUnsupported -> {
                        Text(
                            text = "This device does not support NFC.",
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            color = ColorStone950,
                            textAlign = TextAlign.Center,
                        )
                    }
                    is NfcTabUi.NfcDisabled -> {
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
                    is NfcTabUi.WaitingForTag -> {
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
                    is NfcTabUi.Exchanging -> {
                        CircularProgressIndicator(color = ColorBlue600)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Negotiating handover…",
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            color = ColorStone500,
                        )
                    }
                    is NfcTabUi.ProtocolError -> {
                        Text(
                            text = s.message,
                            fontFamily = Inter,
                            fontSize = 16.sp,
                            color = Color.Red,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = ColorBlue600),
                            shape = RoundedCornerShape(5.dp),
                        ) {
                            Text(
                                "Try again",
                                fontFamily = Inter,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            )
                        }
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
