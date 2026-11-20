package com.spruceid.mobilesdkexample.walletsettings

import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.KeyStore
import com.spruceid.mobile.sdk.rs.Mdoc
import com.spruceid.mobile.sdk.rs.generateTestMdl
import com.spruceid.mobile.sdk.rs.generateTestPhotoId
import com.spruceid.mobilesdkexample.db.WalletActivityLogs
import com.spruceid.mobilesdkexample.utils.Toast
import com.spruceid.mobilesdkexample.utils.getCredentialIdTitleAndIssuer
import com.spruceid.mobilesdkexample.utils.getCurrentSqlDate
import com.spruceid.mobilesdkexample.viewmodels.CredentialPacksViewModel
import com.spruceid.mobilesdkexample.viewmodels.WalletActivityLogsViewModel

/** Signing key used as the DeviceKey of every locally generated test mdoc. */
const val TEST_MDOC_KEY_ALIAS = "testMdl"

suspend fun generateMockMdl(
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel
) = generateMockMdoc(
    displayName = "mDL",
    makeMdoc = ::generateTestMdl,
    credentialPacksViewModel = credentialPacksViewModel,
    walletActivityLogsViewModel = walletActivityLogsViewModel
)

suspend fun generateMockPhotoId(
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel
) = generateMockMdoc(
    displayName = "Photo ID",
    makeMdoc = ::generateTestPhotoId,
    credentialPacksViewModel = credentialPacksViewModel,
    walletActivityLogsViewModel = walletActivityLogsViewModel
)

/** Issue a test mdoc against the shared test signing key, store it, and log the activity. */
private suspend fun generateMockMdoc(
    displayName: String,
    makeMdoc: (KeyStore, String) -> Mdoc,
    credentialPacksViewModel: CredentialPacksViewModel,
    walletActivityLogsViewModel: WalletActivityLogsViewModel
) {
    try {
        val keyManager = KeyManager()
        if (!keyManager.keyExists(TEST_MDOC_KEY_ALIAS)) {
            keyManager.generateSigningKey(TEST_MDOC_KEY_ALIAS)
        }
        val mdoc = makeMdoc(keyManager, TEST_MDOC_KEY_ALIAS)
        val mdocPack = CredentialPack()

        val credentials = mdocPack.addMdoc(mdoc)
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

        Toast.showSuccess("Test $displayName added to your wallet")
    } catch (_: Exception) {
        Toast.showError("Error generating $displayName")
    }
}
