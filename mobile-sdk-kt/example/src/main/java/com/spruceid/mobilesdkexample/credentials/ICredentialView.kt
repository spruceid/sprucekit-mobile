package com.spruceid.mobilesdkexample.credentials

import androidx.compose.runtime.Composable
import com.spruceid.mobile.sdk.CredentialPack

interface ICredentialView {
    var credentialPack: CredentialPack

    @Composable
    fun credentialListItem(withOptions: Boolean): Unit

    @Composable
    fun credentialListItem(): Unit

    @Composable
    fun credentialDetails(): Unit

    @Composable
    fun credentialPreviewAndDetails(): Unit
}
