package com.spruceid.mobilesdkexample.walletsettings

import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.generateTestMdl
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel

suspend fun generateMockMdl(credentialPacksViewModel:CredentialPacksViewModel) {
    try {
        val keyManager = KeyManager()
        val keyAlias = "testMdl"
        if (!keyManager.keyExists(keyAlias)) {
            keyManager.generateSigningKey(keyAlias)
        }
        val mdl = generateTestMdl(KeyManager(), keyAlias)
        val mdocPack = CredentialPack()

        mdocPack.addMdoc(mdl);
        credentialPacksViewModel.saveCredentialPack(mdocPack)
        Toast.showSuccess("Test mDL added to your wallet")
    } catch (_: Exception) {
        Toast.showError("Error generating mDL")
    }
}