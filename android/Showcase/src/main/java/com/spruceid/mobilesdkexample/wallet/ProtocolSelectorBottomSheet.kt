package com.spruceid.mobilesdkexample.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spruceid.mobilesdkexample.R
import com.spruceid.mobilesdkexample.navigation.Screen
import com.spruceid.mobilesdkexample.ui.theme.ColorStone200
import com.spruceid.mobilesdkexample.ui.theme.ColorStone300
import com.spruceid.mobilesdkexample.ui.theme.ColorStone50
import com.spruceid.mobilesdkexample.ui.theme.ColorStone700
import com.spruceid.mobilesdkexample.ui.theme.ColorStone950
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Order in which supported protocols should be listed when present.
private val PROTOCOL_ORDER = listOf("OID4VCI", "OID4VP", "vcapi")

// Protocols the app can currently act on; anything else is shown disabled.
private val SUPPORTED_PROTOCOLS = setOf("OID4VCI", "OID4VP", "vcapi")

private val PROTOCOL_DESCRIPTIONS = mapOf(
    "OID4VCI" to "OpenID for Verifiable Credential Issuance",
    "OID4VP" to "OpenID for Verifiable Presentations",
    "vcapi" to "VCALM exchange (VC-API)",
)

@Composable
fun ProtocolSelectorBottomSheet(
    onClose: () -> Unit, protocols: Map<String, String>, navController: NavController, credentialPackId: String?
) {
    var showSheet by remember {
        mutableStateOf(true)
    }

    val keys: List<String> = remember(protocols) {
        val orderLower = PROTOCOL_ORDER.map { it.lowercase() }
        val protocolKeysLower = protocols.keys.map { it.lowercase() }.toSet()

        val fullList =
            PROTOCOL_ORDER.filter { protocolKeysLower.contains(it.lowercase()) } + protocols.keys.filter { key ->
                !orderLower.contains(key.lowercase())
            }
        fullList.filter { it.lowercase() != "inviterequest" }
    }

    fun navigate(route: String) {
        navController.navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
    }

    fun handleProtocolSelection(protocolName: String, uri: String = "") {
        showSheet = false
        val encodedUrl = URLEncoder.encode(uri, StandardCharsets.UTF_8.toString())
        when (protocolName) {
            "vcapi" -> navigate(Screen.HandleVCALM.route.replace("{url}", encodedUrl))

            "OID4VCI" -> navigate("oid4vci/$encodedUrl")

            "OID4VP" -> {
                val baseRoute = when {
                    uri.startsWith(OID4VP_SCHEME) && !credentialPackId.isNullOrEmpty() ->
                        Screen.HandleOID4VPWithCredentialPack.route.replace(
                            "{credential_pack_id}",
                            credentialPackId
                        )

                    uri.startsWith(OID4VP_SCHEME) ->
                        Screen.HandleOID4VP.route

                    uri.startsWith(MDOC_OID4VP_SCHEME) && !credentialPackId.isNullOrEmpty() ->
                        Screen.HandleMdocOID4VPWithCredentialPack.route.replace(
                            "{credential_pack_id}",
                            credentialPackId
                        )

                    uri.startsWith(MDOC_OID4VP_SCHEME) ->
                        Screen.HandleMdocOID4VP.route

                    else -> throw IllegalArgumentException("Invalid OID4VP scheme")
                }

                val route = baseRoute.replace("{url}", encodedUrl)

                navigate(route)

            }

        }
    }

    Box(
        Modifier.fillMaxSize()
    ) {
        if (showSheet) {
            AppBottomSheet(
                onDismissRequest = {
                    showSheet = false
                    onClose()
                },
                title = "Choose how to continue",
                subtitle = "This process supports multiple protocols, pick one.",
                onCancel = {
                    showSheet = false
                    onClose()
                },
            ) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    keys.forEach { protocol ->
                        val isSupported = SUPPORTED_PROTOCOLS.contains(protocol)
                        Button(
                            onClick = {
                                handleProtocolSelection(
                                    protocol, protocols[protocol] ?: ""
                                )
                            },
                            enabled = isSupported,
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = ColorStone950,
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ColorStone50)
                                    .border(
                                        width = 1.dp,
                                        color = ColorStone300,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(20.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.Start,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = protocol,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSupported) ColorStone700 else ColorStone200
                                    )
                                    Text(
                                        text = PROTOCOL_DESCRIPTIONS[protocol] ?: "",
                                        color = if (isSupported) ColorStone700 else ColorStone200
                                    )
                                }
                                Icon(
                                    painter = painterResource(id = R.drawable.chevron),
                                    contentDescription = "Select this protocol",
                                    tint = if (isSupported) ColorStone700 else ColorStone200,
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}