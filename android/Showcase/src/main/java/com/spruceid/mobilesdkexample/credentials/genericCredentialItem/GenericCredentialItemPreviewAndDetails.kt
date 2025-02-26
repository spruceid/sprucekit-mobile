package com.spruceid.coloradofwd.credentials.genericCredentialItem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobilesdkexample.ui.theme.ColorBase1
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenericCredentialItemPreviewAndDetails(
    statusListViewModel: StatusListViewModel,
    credentialPack: CredentialPack,
    goTo: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onExport: ((String) -> Unit)? = null,
    withOptions: Boolean = true,
) {
    val statusList by statusListViewModel.observeStatusForId(credentialPack.id())
        .collectAsState()
    var sheetOpen by remember { mutableStateOf(false) }

    Box(
        Modifier
            .clickable {
                if (statusList != CredentialStatusList.REVOKED) {
                    goTo?.invoke()
                } else {
                    sheetOpen = true
                }
            }
    ) {
        GenericCredentialItemListItem(
            statusListViewModel = statusListViewModel,
            credentialPack = credentialPack,
            onDelete = onDelete,
            onExport = onExport,
            withOptions = withOptions
        )
    }

    if (sheetOpen) {
        if (statusList != CredentialStatusList.REVOKED) {
            ModalBottomSheet(
                onDismissRequest = {
                    sheetOpen = false
                },
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .nestedScroll(rememberNestedScrollInteropConnection()),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = ColorBase1,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            ) {
                GenericCredentialItemReviewInfo(
                    statusListViewModel = statusListViewModel,
                    credentialPack = credentialPack,
                    onDelete = onDelete,
                    onExport = onExport,
                    withOptions = withOptions
                )
            }
        } else {
            ModalBottomSheet(
                onDismissRequest = {
                    sheetOpen = false
                },
                modifier = Modifier
                    .fillMaxHeight(0.4f)
                    .nestedScroll(rememberNestedScrollInteropConnection()),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = ColorBase1,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            ) {
                GenericCredentialItemRevokedInfo(
                    credentialPack = credentialPack,
                    onClose = {
                        sheetOpen = false
                    }
                )
            }
        }
    }
}