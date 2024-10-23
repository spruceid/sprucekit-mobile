package com.spruceid.mobilesdkexample.wallet

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spruceid.mobile.sdk.CredentialsViewModel
import com.spruceid.mobilesdkexample.ui.theme.Bg
import com.spruceid.mobilesdkexample.ui.theme.Inter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectiveDisclosureView(
    credentialViewModel: CredentialsViewModel,
    onCancel: () -> Unit
) {

    val itemsRequests by credentialViewModel.itemsRequest.collectAsState()
    val allowedNamespaces by credentialViewModel.allowedNamespaces.collectAsState()

    val selectNamespacesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            onCancel()
        },
        modifier = Modifier
            .fillMaxHeight(0.8f),
        sheetState = selectNamespacesSheetState,
        dragHandle = null,
        containerColor = Bg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            Modifier
                .padding(all = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            itemsRequests.map { itemsRequest ->
                Column {
                    Text(
                        text = "Document being requested:\n\t\t${itemsRequest.docType}\n",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    itemsRequest.namespaces.map { namespaceSpec ->
                        Column {
                            Text(
                                text = "The following fields are being requested by the reader:\n",
                                fontFamily = Inter,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = "\t\t${namespaceSpec.key}",
                                fontFamily = Inter,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            namespaceSpec.value.forEach { namespace ->
                                NamespaceField(
                                    namespace = namespace,
                                    isChecked = allowedNamespaces[itemsRequest.docType]?.get(
                                        namespaceSpec.key
                                    )?.contains(namespace.key) ?: false,
                                    onCheck = { _ ->
                                        credentialViewModel.toggleAllowedNamespace(
                                            itemsRequest.docType,
                                            namespaceSpec.key,
                                            namespace.key
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    modifier = Modifier
                        .padding(end = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        contentColor = Color.White,
                    ),
                    onClick = {
                        onCancel()
                    }
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                    )
                }
                Button(onClick = {
                    try {
                        credentialViewModel.submitNamespaces(allowedNamespaces)
                    } catch (e: Error) {
                        Log.e("SelectiveDisclosureView", e.stackTraceToString())
                    }
                }) {
                    Text(
                        text = "Share fields",
                        fontFamily = Inter,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
