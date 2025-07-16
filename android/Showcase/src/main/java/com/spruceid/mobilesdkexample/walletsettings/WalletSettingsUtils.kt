package com.spruceid.mobilesdkexample.walletsettings

import androidx.compose.runtime.Composable
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.generateTestMdl
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.activityHiltViewModel
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel

suspend fun generateMockMdl(credentialPacksViewModel:CredentialPacksViewModel, walletActivityLogsViewModel: WalletActivityLogsViewModel) {
    try {
        val keyManager = KeyManager()
        val keyAlias = "testMdl"
        if (!keyManager.keyExists(keyAlias)) {
            keyManager.generateSigningKey(keyAlias)
        }
        val mdl = generateTestMdl(KeyManager(), keyAlias)
        val mdocPack = CredentialPack()

        var credentials = mdocPack.addMdoc(mdl);
        credentialPacksViewModel.saveCredentialPack(mdocPack)

        val credentialInfo = getCredentialIdTitleAndIssuer(mdocPack, credentials[0])
        walletActivityLogsViewModel.saveWalletActivityLog(
            walletActivityLogs = WalletActivityLogs(
                credentialPackId = mdocPack.id().toString(),
                credentialId = credentialInfo.first,
                credentialTitle = credentialInfo.second,
                issuer = credentialInfo.third,
                action = "Claimed",
                dateTime = getCurrentSqlDate(),
                additionalInformation = ""
            )
        )


        Toast.showSuccess("Test mDL added to your wallet")
    } catch (_: Exception) {
        Toast.showError("Error generating mDL")
    }
}
