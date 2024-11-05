package com.spruceid.mobilesdkexample.wallet

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialsViewModel
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.ParsedCredential
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.Bg
import com.spruceid.mobilesdkexample.ui.theme.CredentialBorder
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.ui.theme.Primary
import com.spruceid.mobilesdkexample.ui.theme.TextHeader
import com.spruceid.mobilesdkexample.ui.theme.TextOnPrimary
import com.spruceid.mobilesdkexample.utils.keyBase64
import com.spruceid.mobilesdkexample.utils.keyPEM
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareableCredentialListItems(
    mdocBase64: String
) {
    val credentialPack = remember {
        CredentialPack()
    }
    val credentials = remember {
        val keyAlias = "someAlias"

        val decodedKey = Base64.getDecoder().decode(
            keyBase64
        )

        val privateKey = KeyFactory.getInstance(
            "EC"
        ).generatePrivate(
            PKCS8EncodedKeySpec(
                decodedKey
            )
        )

        val cert: Array<Certificate> = arrayOf(
            CertificateFactory.getInstance(
                "X.509"
            ).generateCertificate(
                keyPEM.byteInputStream()
            )
        )

        val ks: KeyStore = KeyStore.getInstance(
            "AndroidKeyStore"
        )

        ks.load(
            null
        )

        ks.setKeyEntry(
            keyAlias,
            privateKey,
            null,
            cert
        )


        val mdoc = Mdoc.fromCborEncodedDocument(
            Base64.getDecoder().decode(mdocBase64),
            keyAlias
        )

        credentialPack.addMdoc(mdoc)
    }

    var sheetOpen by remember {
        mutableStateOf(false)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = {
                sheetOpen = false
            },
            modifier = Modifier
                .fillMaxHeight(0.8f),
            sheetState = sheetState,
            dragHandle = null,
            containerColor = Bg,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Review Info",
                textAlign = TextAlign.Center,
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = TextHeader,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
            )
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ShareableCredentialDetailsItem(credential = credentials[0])
            }
        }
    }
    Column(
        Modifier
            .padding(vertical = 10.dp)
            .border(
                width = 1.dp,
                color = CredentialBorder,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp)
                .clickable {
                    sheetOpen = true
                }
        ) {
            ShareableCredentialListItem(credential = credentials[0])
        }
        ShareableCredentialListItemQRCode(credential = credentials[0])
    }
}

@Composable
fun ShareableCredentialDetailsItem(credential: ParsedCredential) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(credential.id())
    }
}

@Composable
fun ShareableCredentialListItem(credential: ParsedCredential) {
    Text(credential.id())
}

@Composable
fun ShareableCredentialListItemQRCode(credential: ParsedCredential) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    var showQRCode by remember {
        mutableStateOf(false)
    }

    fun newCredentialViewModel(): CredentialsViewModel {
        val credentialViewModel = ViewModelProvider.AndroidViewModelFactory(application)
            .create(CredentialsViewModel::class.java)
        credentialViewModel.storeCredential(credential)
        return credentialViewModel
    }

    val credentialViewModel by remember {
        mutableStateOf(newCredentialViewModel())
    }

    fun cancel() {
        showQRCode = false
        credentialViewModel.cancel()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape = RoundedCornerShape(0.dp, 0.dp, 8.dp, 8.dp))
            .background(Primary)
            .padding(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    showQRCode = !showQRCode
                    if (!showQRCode) {
                        credentialViewModel.cancel()
                    }
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.qrcode),
                contentDescription = stringResource(R.string.toggle_display_qr_code),
                modifier = Modifier.width(12.dp)
            )
            Text(
                text = if (showQRCode) "Hide QR code" else "Show QR code",
                modifier = Modifier.padding(start = 6.dp),
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 10.sp,
                color = TextOnPrimary
            )
        }

        AnimatedVisibility(visible = showQRCode) {
            if (showQRCode) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ShareView(
                        credentialViewModel = credentialViewModel,
                        onCancel = {
                            cancel()
                        }
                    )
                    Text(
                        text = "Shares your credential online or\nin-person, wherever accepted.",
                        modifier = Modifier.padding(start = 6.dp),
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 10.sp,
                        color = TextOnPrimary
                    )
                }
            }
        }
    }
}