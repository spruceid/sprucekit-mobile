package com.spruceid.mobilesdkexample.credentials

import androidx.compose.runtime.Composable

interface ICredentialView {
    @Composable
    fun credentialListItem(withOptions: Boolean): Unit

    @Composable
    fun credentialListItem(): Unit

    @Composable
    fun credentialDetails(): Unit

    @Composable
    fun credentialPreviewAndDetails(): Unit
}
