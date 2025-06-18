package com.spruceid.mobilesdkexample.verifier

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobilesdkexample.credentials.ICredentialView
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.credentialDisplaySelector
import com.spruceid.mobilesdkexample.utils.splitCamelCase
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel

@Composable
fun VerifierCredentialSuccessView(
    rawCredential: String,
    onClose: () -> Unit,
    logVerification: (String, String, String) -> Unit,
    statusListViewModel: StatusListViewModel
) {
    var credentialItem by remember { mutableStateOf<ICredentialView?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var issuer by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        credentialItem = credentialDisplaySelector(
            rawCredential,
            statusListViewModel = statusListViewModel,
            null,
            null,
            null
        )
        statusListViewModel.fetchAndUpdateStatus(credentialItem!!.credentialPack)
        val credential = credentialItem!!.credentialPack.list().first()
        val claims = credentialItem!!.credentialPack.getCredentialClaims(
            credential,
            listOf("name", "type", "description", "issuer", "Given Names", "Family Name")
        )

        try {
            title = claims.optString("name")?.takeIf { it.isNotBlank() }
            if (title.isNullOrBlank()) {
                val arrayTypes = claims.optJSONArray("type")
                if (arrayTypes != null) {
                    for (i in 0 until arrayTypes.length()) {
                        if (arrayTypes.get(i).toString() != "VerifiableCredential") {
                            title = arrayTypes.get(i).toString().splitCamelCase()
                            break
                        }
                    }
                }
            }
            if (title.isNullOrBlank()) {
                val names = claims.get("Given Names").toString()
                val family = claims.get("Family Name").toString()
                title = "$names $family"
            }
        } catch (_: Exception) {
        }

        try {
            issuer = claims.getJSONObject("issuer").getString("name").toString()
        } catch (_: Exception) {
        }

        logVerification(
            title ?: "",
            issuer ?: "",
            statusListViewModel.getStatus(credentialItem!!.credentialPack).toString()
        )
    }

    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .padding(top = 30.dp)
                .padding(horizontal = 24.dp)
        ) {
            title?.let {
                Text(
                    text = it,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = ColorStone950,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            issuer?.let {
                Text(
                    text = it,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = ColorStone600
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
        }

        Column(
            Modifier
                .fillMaxSize()
                .weight(weight = 1f, fill = false)
        ) {
            if (credentialItem != null) {
                credentialItem!!.credentialDetails()
            }
        }
//        TODO: implement restart flow
//        Button(
//            onClick = {
//                onRestart()
//            },
//            shape = RoundedCornerShape(5.dp),
//            colors = ButtonDefaults.buttonColors(
//                containerColor = ColorStone950,
//                contentColor = Color.White,
//            ),
//            modifier = Modifier
//                .fillMaxWidth()
//        ) {
//            Row(
//                verticalAlignment = Alignment.CenterVertically,
//                horizontalArrangement = Arrangement.Center
//            ) {
//                Image(
//                    painter = painterResource(id = R.drawable.arrow_circle),
//                    contentDescription = stringResource(id = R.string.arrow_circle)
//                )
//                Text(
//                    text = "Rescan",
//                    fontFamily = Inter,
//                    fontWeight = FontWeight.SemiBold,
//                    color = Color.White,
//                )
//            }
//        }

        Button(
            onClick = {
                onClose()
            },
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = ColorStone950,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = ColorStone300,
                    shape = RoundedCornerShape(6.dp)
                )
        ) {
            Text(
                text = "Close",
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                color = ColorStone950,
            )
        }
    }
}
