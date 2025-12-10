package com.spruceid.mobilesdkexample.credentials

import android.content.ComponentName
import com.spruceid.mobile.sdk.nfc.BaseNfcPresentationService
import com.spruceid.mobile.sdk.nfc.NfcPresentationError
import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo
import com.spruceid.mobilesdkexample.utils.Toast

class NfcPresentationService : BaseNfcPresentationService() {

    override fun shouldPerformHandoverEngagement(): Boolean {
        return appInForeground() && shareScreenCallback != null
    }

    override fun negotiationFailed(error: NfcPresentationError) {

        if (!appInForeground()) {
            return // Should be unreachable
        }
        Toast.showError(error.humanReadable)
    }

    override fun negotiatedTransport(carrierInfo: NegotiatedCarrierInfo) {
        shareScreenCallback?.let {
            it(carrierInfo)
        } ?: run {
            Toast.showWarning("Please select a credential.")
        }
    }

    override fun componentName(): ComponentName {
        return ComponentName(applicationContext, NfcPresentationService::class.java)
    }

    companion object {
        var shareScreenCallback: ((carrierInfo: NegotiatedCarrierInfo) -> Unit)? = null
    }
}
