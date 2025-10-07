package com.spruceid.mobilesdkexample.credentials

import androidx.compose.runtime.Composable
import com.spruceid.mobile.sdk.CredentialPack

interface ICredentialView {
    var credentialPack: CredentialPack

    @Composable
    fun CredentialListItem(withOptions: Boolean): Unit

    @Composable
    fun CredentialListItem(): Unit

    @Composable
    fun CredentialDetails(): Unit

    @Composable
    fun CredentialReviewInfo(footerActions: @Composable () -> Unit): Unit

    @Composable
    fun CredentialRevokedInfo(onClose: () -> Unit): Unit

    @Composable
    fun CredentialPreviewAndDetails(): Unit
}
