package com.spruceid.coloradofwd.credentials.genericCredentialItem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import com.spruceid.mobilesdkexample.ui.theme.Inter
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel

@Composable
fun GenericCredentialItemReviewInfo(
    statusListViewModel: StatusListViewModel,
    credentialPack: CredentialPack,
    onDelete: (() -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    withOptions: Boolean = false,
    footerActions: @Composable (() -> Unit)? = null,
    customItemListItem: @Composable ((StatusListViewModel, CredentialPack, (() -> Unit)?, ((String) -> Unit)?, Boolean) -> Unit)? = null,
    customCredentialItemDetails: @Composable ((StatusListViewModel, CredentialPack) -> Unit)? = null
) {
    Column(
        Modifier
            .padding(all = 20.dp)
            .padding(top = 20.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            text = "Review Info",
            textAlign = TextAlign.Center,
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = ColorStone950,
            modifier = Modifier
                .fillMaxWidth()
        )
        // Header
        if (customItemListItem != null) {
            customItemListItem(
                statusListViewModel,
                credentialPack,
                onDelete,
                onExport,
                withOptions
            )
        } else {
            GenericCredentialItemListItem(
                statusListViewModel = statusListViewModel,
                credentialPack = credentialPack,
                onDelete = onDelete,
                onExport = onExport,
                withOptions = withOptions
            )
        }

        // Body
        Column(
            Modifier
                .fillMaxSize()
                .weight(weight = 1f, fill = false)
        ) {
            if (customCredentialItemDetails != null) {
                customCredentialItemDetails(
                    statusListViewModel,
                    credentialPack
                )
            } else {
                GenericCredentialItemDetails(
                    statusListViewModel = statusListViewModel,
                    credentialPack = credentialPack
                )
            }
        }

        // Footer
        footerActions?.invoke()
    }
}