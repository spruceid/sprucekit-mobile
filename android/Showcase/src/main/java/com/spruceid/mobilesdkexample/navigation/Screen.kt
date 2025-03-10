package com.spruceid.mobilesdkexample.navigation

const val HOME_SCREEN_PATH = "home/{tab}"
const val VERIFY_DL_PATH = "verify_dl"
const val VERIFY_EA_PATH = "verify_ea"
const val VERIFY_VC_PATH = "verify_vc"
const val VERIFY_CWT_PATH = "verify_CWT"
const val VERIFY_MDOC_PATH = "verify_mdoc"
const val VERIFY_MDL_OVER_18_PATH = "verify_mdl_over_18"
const val VERIFY_DELEGATED_OID4VP_PATH = "verify_delegated_oid4vp/{id}"
const val VERIFIER_SETTINGS_HOME_PATH = "verifier_settings_home"
const val VERIFIER_SETTINGS_ACTIVITY_LOG = "verifier_settings_activity_log"
const val VERIFIER_SETTINGS_TRUSTED_CERTIFICATES = "verifier_settings_trusted_certificates"
const val ADD_VERIFICATION_METHOD_PATH = "add_verification_method"
const val WALLET_SETTINGS_HOME_PATH = "wallet_settings_home"
const val WALLET_SETTINGS_ACTIVITY_LOG = "wallet_settings_activity_log"
const val ADD_TO_WALLET_PATH = "add_to_wallet/{rawCredential}"
const val SCAN_QR_PATH = "scan_qr"
const val HANDLE_OID4VCI_PATH = "oid4vci/{url}"
const val HANDLE_OID4VP_PATH = "oid4vp/{url}"
const val HANDLE_MDOC_OID4VP_PATH = "mdoc_oid4vp/{url}"
const val CREDENTIAL_DETAILS_PATH = "credential_details/{credential_pack_id}"

sealed class Screen(val route: String) {
    object HomeScreen : Screen(HOME_SCREEN_PATH)
    object VerifyDLScreen : Screen(VERIFY_DL_PATH)
    object VerifyEAScreen : Screen(VERIFY_EA_PATH)
    object VerifyVCScreen : Screen(VERIFY_VC_PATH)
    object VerifyCWTScreen : Screen(VERIFY_CWT_PATH)
    object VerifyMDocScreen : Screen(VERIFY_MDOC_PATH)
    object VerifyMDlOver18Screen : Screen(VERIFY_MDL_OVER_18_PATH)
    object VerifyDelegatedOid4vpScreen : Screen(VERIFY_DELEGATED_OID4VP_PATH)
    object VerifierSettingsHomeScreen : Screen(VERIFIER_SETTINGS_HOME_PATH)
    object VerifierSettingsActivityLogScreen : Screen(VERIFIER_SETTINGS_ACTIVITY_LOG)
    object AddVerificationMethodScreen : Screen(ADD_VERIFICATION_METHOD_PATH)
    object WalletSettingsHomeScreen : Screen(WALLET_SETTINGS_HOME_PATH)
    object WalletSettingsActivityLogScreen : Screen(WALLET_SETTINGS_ACTIVITY_LOG)
    object VerifierSettingsTrustedCertificatesScreen :
        Screen(VERIFIER_SETTINGS_TRUSTED_CERTIFICATES)

    object AddToWalletScreen : Screen(ADD_TO_WALLET_PATH)
    object ScanQRScreen : Screen(SCAN_QR_PATH)
    object HandleOID4VCI : Screen(HANDLE_OID4VCI_PATH)
    object HandleOID4VP : Screen(HANDLE_OID4VP_PATH)
    object HandleMdocOID4VP : Screen(HANDLE_MDOC_OID4VP_PATH)
    object CredentialDetailsScreen : Screen(CREDENTIAL_DETAILS_PATH)
}
