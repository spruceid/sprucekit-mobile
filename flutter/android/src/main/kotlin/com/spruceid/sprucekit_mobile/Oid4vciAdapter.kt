package com.spruceid.sprucekit_mobile

import android.content.Context
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.Oid4vciAsyncHttpClient
import com.spruceid.mobile.sdk.rs.CredentialFormat as RsCredentialFormat
import com.spruceid.mobile.sdk.rs.CredentialResponse
import com.spruceid.mobile.sdk.rs.CredentialTokenState
import com.spruceid.mobile.sdk.rs.JwsSigner
import com.spruceid.mobile.sdk.rs.JwsSignerInfo
import com.spruceid.mobile.sdk.rs.Oid4vciClient
import com.spruceid.mobile.sdk.rs.Proofs
import com.spruceid.mobile.sdk.rs.createJwtProof
import com.spruceid.mobile.sdk.rs.decodeDerSignature
import com.spruceid.mobile.sdk.rs.generateDidJwkUrl
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
                    keyId = keyId
                )
                callback(Result.success(result))
            } catch (e: Exception) {
                callback(Result.success(Oid4vciError(message = e.localizedMessage ?: "Unknown error")))
            }
        }
    }

    private suspend fun performIssuance(
        credentialOffer: String,
        keyId: String
    ): Oid4vciResult {
        // Setup HTTP client
        val httpClient = Oid4vciAsyncHttpClient()

        // Setup key and signer
        val keyManager = KeyManager()
        val jwk = keyManager.getOrInsertJwk(keyId)
        val didUrl = generateDidJwkUrl(jwk)
        jwk.setKid(didUrl.toString())

        val signer = object : JwsSigner {
            override suspend fun fetchInfo(): JwsSignerInfo {
                return jwk.fetchInfo()
            }

            override suspend fun signBytes(signingBytes: ByteArray): ByteArray {
                val signature = keyManager.signPayload(keyId, signingBytes)
                    ?: throw IllegalStateException("Failed to sign payload")
                return decodeDerSignature(signature)
            }
        }

        // Derive client ID from key's DID
        val derivedClientId = didUrl.did().toString()
        val oid4vciClient = Oid4vciClient(derivedClientId)

        // Resolve offer URL
        val offerUrl = if (credentialOffer.startsWith("openid-credential-offer://")) {
            credentialOffer
        } else {
            "openid-credential-offer://$credentialOffer"
        }

        val offer = oid4vciClient.resolveOfferUrl(httpClient, offerUrl)
        val credentialIssuer = offer.credentialIssuer()

        // Accept offer
        when (val state = oid4vciClient.acceptOffer(httpClient, offer)) {
            is CredentialTokenState.RequiresAuthorizationCode -> {
                return Oid4vciError(message = "Authorization Code Grant not supported")
            }
            is CredentialTokenState.RequiresTxCode -> {
                return Oid4vciError(message = "Transaction Code not supported")
            }
            is CredentialTokenState.Ready -> {
                val credentialToken = state.v1
                val credentialId = credentialToken.defaultCredentialId()

                // Generate Proof of Possession
                val nonce = credentialToken.getNonce(httpClient)
                val jwt = createJwtProof(derivedClientId, credentialIssuer, null, nonce, signer)
                val proofs = Proofs.Jwt(listOf(jwt))

                // Exchange credential
                val response = oid4vciClient.exchangeCredential(httpClient, credentialToken, credentialId, proofs)

                when (response) {
                    is CredentialResponse.Deferred -> {
                        return Oid4vciError(message = "Deferred credentials not supported")
                    }
                    is CredentialResponse.Immediate -> {
                        val issuedCredentials = response.v1.credentials.map { cred ->
                            val formatString = when (cred.format) {
                                is RsCredentialFormat.MsoMdoc -> "mso_mdoc"
                                is RsCredentialFormat.JwtVcJson -> "jwt_vc_json"
                                is RsCredentialFormat.JwtVcJsonLd -> "jwt_vc_json-ld"
                                is RsCredentialFormat.LdpVc -> "ldp_vc"
                                is RsCredentialFormat.Vcdm2SdJwt -> "vc+sd-jwt"
                                is RsCredentialFormat.DcSdJwt -> "dc+sd-jwt"
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
            }
        }
    }
}
