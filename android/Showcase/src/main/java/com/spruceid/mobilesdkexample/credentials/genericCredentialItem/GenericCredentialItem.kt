package com.spruceid.mobilesdkexample.credentials.genericCredentialItem

import androidx.compose.runtime.Composable
import com.spruceid.coloradofwd.credentials.genericCredentialItem.GenericCredentialItemDetails
import com.spruceid.coloradofwd.credentials.genericCredentialItem.GenericCredentialItemListItem
import com.spruceid.coloradofwd.credentials.genericCredentialItem.GenericCredentialItemPreviewAndDetails
import com.spruceid.coloradofwd.credentials.genericCredentialItem.GenericCredentialItemReviewInfo
import com.spruceid.coloradofwd.credentials.genericCredentialItem.GenericCredentialItemRevokedInfo
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobilesdkexample.credentials.ICredentialView
import com.spruceid.mobilesdkexample.utils.addCredential
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel

class GenericCredentialItem : ICredentialView {
    override var credentialPack: CredentialPack
    private val statusListViewModel: StatusListViewModel
    private val goTo: (() -> Unit)?
    private val onDelete: (() -> Unit)?
    private val onExport: ((String) -> Unit)?

    constructor(
        credentialPack: CredentialPack,
        statusListViewModel: StatusListViewModel,
        goTo: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        onExport: ((String) -> Unit)? = null
    ) {
        this.credentialPack = credentialPack
        this.goTo = goTo
        this.onDelete = onDelete
        this.onExport = onExport
        this.statusListViewModel = statusListViewModel
    }

    constructor(
        rawCredential: String,
        statusListViewModel: StatusListViewModel,
        goTo: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null,
        onExport: ((String) -> Unit)? = null
    ) {
        this.credentialPack = addCredential(CredentialPack(), rawCredential)
        this.goTo = goTo
        this.onDelete = onDelete
        this.onExport = onExport
        this.statusListViewModel = statusListViewModel
    }

    @Composable
    override fun credentialListItem(withOptions: Boolean) {
        GenericCredentialItemListItem(
            statusListViewModel = statusListViewModel,
            credentialPack = credentialPack,
            onDelete = onDelete,
            onExport = onExport,
            withOptions = withOptions
        )
    }

    @Composable
    override fun credentialListItem() {
        GenericCredentialItemListItem(
            statusListViewModel = statusListViewModel,
            credentialPack = credentialPack,
            onDelete = onDelete,
            onExport = onExport,
            withOptions = false
        )
    }

    @Composable
    override fun credentialDetails() {
        GenericCredentialItemDetails(statusListViewModel, credentialPack)
    }

    @Composable
    override fun credentialReviewInfo(footerActions: @Composable () -> Unit) {
        GenericCredentialItemReviewInfo(
            statusListViewModel = statusListViewModel,
            credentialPack = credentialPack,
            footerActions = footerActions
        )
    }

    @Composable
    override fun credentialRevokedInfo(onClose: () -> Unit) {
        GenericCredentialItemRevokedInfo(credentialPack, onClose)
    }

    @Composable
    override fun credentialPreviewAndDetails() {
        GenericCredentialItemPreviewAndDetails(
            statusListViewModel = statusListViewModel,
            credentialPack = credentialPack,
            goTo = goTo,
            onDelete = onDelete,
            onExport = onExport,
            withOptions = true
        )
    }
}


