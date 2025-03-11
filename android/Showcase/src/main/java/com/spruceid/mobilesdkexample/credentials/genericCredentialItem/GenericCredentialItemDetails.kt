package com.spruceid.coloradofwd.credentials.genericCredentialItem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spruceid.mobile.sdk.CredentialPack
import com.spruceid.mobile.sdk.CredentialStatusList
import com.spruceid.mobile.sdk.ui.BaseCard
import com.spruceid.mobile.sdk.ui.CardRenderingDetailsField
import com.spruceid.mobile.sdk.ui.CardRenderingDetailsView
import com.spruceid.mobile.sdk.ui.toCardRendering
import com.spruceid.mobilesdkexample.credentials.CredentialStatus
import com.spruceid.mobilesdkexample.credentials.genericObjectDisplayer
import com.spruceid.mobilesdkexample.viewmodels.StatusListViewModel

@Composable
fun GenericCredentialItemDetails(
    statusListViewModel: StatusListViewModel,
    credentialPack: CredentialPack
) {
    val statusList by statusListViewModel.observeStatusForId(credentialPack.id())
        .collectAsState()
    val detailsRendering = CardRenderingDetailsView(
        fields = listOf(
            CardRenderingDetailsField(
                // it's also possible just request the credentialSubject and cast it to JSONObject
                keys = listOf(),
                formatter = { values ->
                    val credential = values.toList().firstNotNullOfOrNull {
                        val cred = credentialPack.getCredentialById(it.first)
                        try {
                            if (
                                cred?.asJwtVc() != null ||
                                cred?.asJsonVc() != null ||
                                cred?.asSdJwt() != null ||
                                cred?.asMsoMdoc() != null ||
                                cred?.asCwt() != null
                            ) {
                                it.second
                            } else {
                                null
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    Column {
                        CredentialStatus(
                            statusList ?: CredentialStatusList.UNDEFINED
                        )
                        if (credential != null) {
                            genericObjectDisplayer(
                                credential,
                                listOf(
                                    "type",
                                    "hashed",
                                    "salt",
                                    "proof",
                                    "renderMethod",
                                    "@context",
                                    "credentialStatus",
                                    "-65537"
                                )
                            )
                        }
                    }
                }
            )
        )
    )

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        BaseCard(
            credentialPack = credentialPack,
            rendering = detailsRendering.toCardRendering()
        )
    }
}