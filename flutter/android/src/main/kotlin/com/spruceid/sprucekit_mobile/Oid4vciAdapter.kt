package com.spruceid.sprucekit_mobile

import android.content.Context
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.rs.AsyncHttpClient
import com.spruceid.mobile.sdk.rs.CredentialFormat as RsCredentialFormat
import com.spruceid.mobile.sdk.rs.DidMethod as RsDidMethod
import com.spruceid.mobile.sdk.rs.HttpRequest
import com.spruceid.mobile.sdk.rs.HttpResponse
import com.spruceid.mobile.sdk.rs.Oid4vci as RsOid4vci
import com.spruceid.mobile.sdk.rs.Oid4vciExchangeOptions as RsOid4vciExchangeOptions
import com.spruceid.mobile.sdk.rs.generatePopComplete
import com.spruceid.mobile.sdk.rs.generatePopPrepare
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpMethod
import io.ktor.util.toMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * OID4VCI Pigeon Adapter
 *
 * Handles OpenID for Verifiable Credential Issuance flow
 */
internal class Oid4vciAdapter(private val context: Context) : Oid4vci {

    override fun runIssuance(
        credentialOffer: String,
        clientId: String,
        redirectUrl: String,
        keyId: String,
        didMethod: DidMethod,
        contextMap: Map<String, String>?,
        callback: (Result<Oid4vciResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = performIssuance(
                    credentialOffer = credentialOffer,
                    clientId = clientId,
                    redirectUrl = redirectUrl,
                    keyId = keyId,
                    didMethod = didMethod,
                    contextMap = contextMap
                )
                callback(Result.success(result))
            } catch (e: Exception) {
                callback(Result.success(Oid4vciError(message = e.localizedMessage ?: "Unknown error")))
            }
        }
    }

    private suspend fun performIssuance(
        credentialOffer: String,
        clientId: String,
        redirectUrl: String,
        keyId: String,
        didMethod: DidMethod,
        contextMap: Map<String, String>?
    ): Oid4vciResult {
        val client = HttpClient(CIO)

        // Create OID4VCI session with async HTTP client
        val oid4vciSession = RsOid4vci.newWithAsyncClient(
            client = object : AsyncHttpClient {
                override suspend fun httpClient(request: HttpRequest): HttpResponse {
                    val res = client.request(request.url) {
                        method = HttpMethod(request.method)
                        for ((k, v) in request.headers) {
                            headers[k] = v
                        }
                        setBody(request.body)
                    }

                    return HttpResponse(
                        statusCode = res.status.value.toUShort(),
                        headers = res.headers.toMap().mapValues { it.value.joinToString() },
                        body = res.readBytes()
                    )
                }
            }
        )

        // Initiate with offer
        oid4vciSession.initiateWithOffer(
            credentialOffer = credentialOffer,
            clientId = clientId,
            redirectUrl = redirectUrl
        )

        // Exchange token to get nonce
        val nonce = oid4vciSession.exchangeToken()

        // Get metadata
        val metadata = oid4vciSession.getMetadata()

        // Ensure signing key exists
        val keyManager = KeyManager()
        if (!keyManager.keyExists(keyId)) {
            keyManager.generateSigningKey(keyId)
        }

        // Get JWK
        val jwk = keyManager.getJwk(id = keyId)
            ?: return Oid4vciError(message = "Failed to get JWK for key: $keyId")

        // Convert DID method
        val rsDidMethod = when (didMethod) {
            DidMethod.JWK -> RsDidMethod.JWK
            DidMethod.KEY -> RsDidMethod.KEY
        }

        // Generate Proof of Possession
        val signingInput = generatePopPrepare(
            audience = metadata.issuer(),
            nonce = nonce,
            didMethod = rsDidMethod,
            publicJwk = jwk,
            durationInSecs = null
        )

        val signature = keyManager.signPayload(
            id = keyId,
            payload = signingInput
        ) ?: return Oid4vciError(message = "Failed to sign payload")

        val pop = generatePopComplete(
            signingInput = signingInput,
            signatureDer = signature
        )

        // Set context map if provided
        contextMap?.let {
            oid4vciSession.setContextMap(it)
        }

        // Exchange credential
        val credentials = oid4vciSession.exchangeCredential(
            proofsOfPossession = listOf(pop),
            options = RsOid4vciExchangeOptions(false)
        )

        // Convert to issued credentials
        val issuedCredentials = credentials.map { cred ->
            val formatString = when (cred.format) {
                is RsCredentialFormat.MsoMdoc -> "mso_mdoc"
                is RsCredentialFormat.JwtVcJson -> "jwt_vc_json"
                is RsCredentialFormat.JwtVcJsonLd -> "jwt_vc_json_ld"
                is RsCredentialFormat.LdpVc -> "ldp_vc"
                is RsCredentialFormat.Vcdm2SdJwt -> "vcdm2_sd_jwt"
                is RsCredentialFormat.Cwt -> "cwt"
                is RsCredentialFormat.Other -> (cred.format as RsCredentialFormat.Other).v1
            }
            IssuedCredential(
                payload = cred.payload.toString(Charsets.UTF_8),
                format = formatString
            )
        }

        return Oid4vciSuccess(credentials = issuedCredentials)
    }
}
