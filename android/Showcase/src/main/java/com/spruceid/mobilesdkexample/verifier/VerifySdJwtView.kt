package com.spruceid.mobilesdkexample.verifier

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.spruceid.mobile.sdk.rs.verifySdJwtVp
import com.spruceid.mobilesdkexample.ScanningComponent
import com.spruceid.mobilesdkexample.ScanningType
import com.spruceid.mobilesdkexample.navigation.Screen
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Scan + verify a compact SD-JWT VP (the QR payload produced by
 * `generateCredentialVpToken`). Validates issuer signature via DID resolution
 * (`AnyDidMethod`) — for `did:jwk` issuers this is fully offline.
 *
 * At V40 QR density, ML Kit occasionally returns a "successfully decoded"
 * string with a few flipped bytes — passes QR-level CRC but fails downstream
 * signature verification. We keep the scanner running on failure and silently
 * retry the next frame, only surfacing an error after [MAX_SCAN_ATTEMPTS]
 * consecutive failures (which would indicate a real problem rather than
 * frame noise).
 */
private const val MAX_SCAN_ATTEMPTS = 5

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VerifySdJwtView(
    navController: NavController
) {
    var success by remember {
        mutableStateOf<Boolean?>(null)
    }
    var failureCount by remember { mutableStateOf(0) }

    fun onRead(content: String) {
        GlobalScope.launch {
            try {
                verifySdJwtVp(input = content)
                failureCount = 0
                success = true
            } catch (e: Exception) {
                failureCount++
                e.printStackTrace()
                if (failureCount >= MAX_SCAN_ATTEMPTS) {
                    failureCount = 0
                    success = false
                }
                // Otherwise: silent retry — scanner stays open, next frame
                // gets a shot. User just sees a slightly-longer scan.
            }
        }
    }

    fun back() {
        navController.navigate(
            Screen.HomeScreen.route.replace("{tab}", "verifier")
        ) {
            popUpTo(0)
        }
    }

    if (success == null) {
        // Compressed SD-JWT VPs land at QR V37+ density, which the default
        // ZXing-backed `QRCODE` scanner can't read reliably. The
        // `QRCODE_HIGH_DENSITY` variant routes to the ML Kit-backed scanner.
        // See `MlKitQRCodeScanner` for context. Other scanning surfaces in
        // the showcase keep using `QRCODE` (ZXing) — this scope is opt-in.
        ScanningComponent(
            scanningType = ScanningType.QRCODE_HIGH_DENSITY,
            onRead = ::onRead,
            onCancel = ::back
        )
    } else {
        VerifierBinarySuccessView(
            success = success!!,
            description = if (success!!) "Valid SD-JWT VP" else "Invalid SD-JWT VP",
            onClose = ::back
        )
    }
}
