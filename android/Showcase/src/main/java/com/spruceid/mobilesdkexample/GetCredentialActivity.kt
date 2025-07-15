package com.spruceid.mobilesdkexample;

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.rive.runtime.kotlin.RiveAnimationView
import app.rive.runtime.kotlin.core.ExperimentalAssetLoader
import com.spruceid.mobile.sdk.rs.FieldId180137
import com.spruceid.mobile.sdk.rs.RequestMatch180137
import com.spruceid.mobilesdkexample.wallet.MdocFieldSelector
import com.spruceid.mobile.sdk.dcapi.Activity as DcApiActivity

class GetCredentialActivity() : DcApiActivity() {
    @Composable
    override fun ConsentView(
        match: RequestMatch180137,
        origin: String,
        onContinue: (List<FieldId180137>) -> Unit,
        onCancel: () -> Unit
    ) {
        MdocFieldSelector(
            match = match,
            onContinue = { onContinue(it.approvedFields) },
            onCancel = onCancel,
            innerColumnModifier = Modifier,
            origin = origin
        )
    }

    @OptIn(ExperimentalAssetLoader::class)
    @Composable
    override fun LoadingView() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AndroidView(
                modifier = Modifier.size(60.dp),
                factory = { context ->
                    RiveAnimationView(context).also {
                        it.setRiveResource(
                            resId = R.raw.loading_spinner,
                        )
                    }
                },
            )
        }
    }
}