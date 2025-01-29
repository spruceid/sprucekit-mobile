package com.spruceid.mobilesdkexample.walletsettings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.generateTestMdl
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import kotlinx.coroutines.launch

@Composable
fun GenerateMockMdlButton(
    credentialPacksViewModel: CredentialPacksViewModel
) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
            .clickable {
                scope.launch {
                    val keyManager = KeyManager()
                    val keyAlias = "testMdl"
                    if (!keyManager.keyExists(keyAlias)) {
                        keyManager.generateSigningKey(keyAlias)
                    }
                    val mdl = generateTestMdl(KeyManager(), keyAlias)
                    val mdocPack =
                        try {
                            credentialPacksViewModel.credentialPacks.value.first { pack ->
                                pack
                                    .list()
                                    .any { credential -> credential.asMsoMdoc() != null }
                            }
                        } catch (error: NoSuchElementException) {
                            CredentialPack()
                        }
                    if (mdocPack
                            .list()
                            .isEmpty()
                    ) {
                        mdocPack.addMdoc(mdl);
                        credentialPacksViewModel.saveCredentialPack(mdocPack)
                    } else {
                        Toast.showWarning("You already have an mDL")
                    }
                }
            },
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.unknown),
                        contentDescription = stringResource(id = R.string.unknown_check),
                        modifier = Modifier.padding(end = 5.dp),
                    )
                    Text(
                        text = "Generate mDL",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp,
                        color = ColorStone950,
                        modifier = Modifier.padding(bottom = 5.dp, top = 5.dp),
                    )
                }

                Image(
                    painter = painterResource(id = R.drawable.chevron),
                    contentDescription = stringResource(id = R.string.chevron),
                    modifier = Modifier.scale(0.5f)
                )
            }

            Text(
                text = "Generate a fresh test mDL issued by the SpruceID Test CA",
                fontFamily = Inter,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                color = ColorStone600,
            )
        }
    }
}
