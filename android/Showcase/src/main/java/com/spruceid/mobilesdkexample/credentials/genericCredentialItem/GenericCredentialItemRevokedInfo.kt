package com.spruceid.coloradofwd.credentials.genericCredentialItem

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.ui.theme.ColorRose700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone600
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer

@Composable
fun GenericCredentialItemRevokedInfo(
    credentialPack: CredentialPack,
    onClose: () -> Unit
) {
    val credentialTitleAndIssuer = getCredentialIdTitleAndIssuer(credentialPack)
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Revoked Credential",
            textAlign = TextAlign.Left,
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = ColorStone950,
            modifier = Modifier
                .fillMaxWidth()
        )
        Text(
            text = "The following credential(s) have been revoked:",
            textAlign = TextAlign.Left,
            fontFamily = Inter,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            color = ColorStone600,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        )
        Text(
            text = credentialTitleAndIssuer.second,
            textAlign = TextAlign.Left,
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = ColorRose700,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 45.dp)
        )

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