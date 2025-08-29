package com.spruceid.mobilesdkexample.credentials

// import android.widget.Toast
import android.content.ComponentName
import com.spruceid.mobile.sdk.nfc.BaseNfcPresentationService
import com.spruceid.mobile.sdk.nfc.NfcPresentationError
import com.spruceid.mobile.sdk.rs.NegotiatedCarrierInfo
import com.spruceid.mobilesdkexample.utils.Toast

class NfcPresentationService : BaseNfcPresentationService() {

    override fun negotiationFailed(error: NfcPresentationError) {

        if (!appInForeground()) {
            // TODO: Launch wallet activity?
        }
        // Toast.makeText(applicationContext, error.humanReadable, Toast.LENGTH_LONG).show()
        Toast.showError(error.humanReadable)
    }

    override fun negotiatedTransport(carrierInfo: NegotiatedCarrierInfo) {}

    override fun componentName(): ComponentName {
        return ComponentName(applicationContext, NfcPresentationService::class.java)
    }
}
