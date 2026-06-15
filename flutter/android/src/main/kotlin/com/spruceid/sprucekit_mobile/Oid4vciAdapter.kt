package com.spruceid.sprucekit_mobile

import android.content.Context
import com.spruceid.mobile.sdk.KeyManager
import com.spruceid.mobile.sdk.Oid4vciAsyncHttpClient
import com.spruceid.mobile.sdk.rs.CredentialFormat as RsCredentialFormat
import com.spruceid.mobile.sdk.rs.CredentialOrConfigurationId
import com.spruceid.mobile.sdk.rs.CredentialResponse
import com.spruceid.mobile.sdk.rs.GrantType as RsGrantType
import com.spruceid.mobile.sdk.rs.InputMode as RsInputMode
import com.spruceid.mobile.sdk.rs.JwsSigner
import com.spruceid.mobile.sdk.rs.JwsSignerInfo
import com.spruceid.mobile.sdk.rs.Oid4vciCompatibilityMode
import com.spruceid.mobile.sdk.rs.Oid4vciException
import com.spruceid.mobile.sdk.rs.Oid4vciFacadeClient
import com.spruceid.mobile.sdk.rs.Oid4vciFacadeCredentialToken
import com.spruceid.mobile.sdk.rs.Oid4vciFacadeCredentialTokenState
import com.spruceid.mobile.sdk.rs.Oid4vciFacadeResolvedOffer
import com.spruceid.mobile.sdk.rs.Oid4vciFacadeWaitingForAuthorizationCode
import com.spruceid.mobile.sdk.rs.Proofs
import com.spruceid.mobile.sdk.rs.createJwtProof
import com.spruceid.mobile.sdk.rs.decodeDerSignature
import com.spruceid.mobile.sdk.rs.generateDidJwkUrl
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * OID4VCI Pigeon Adapter
 *
 * Handles OpenID for Verifiable Credential Issuance flow
 */
internal class Oid4vciAdapter(private val context: Context) : Oid4vci {

    private data class SessionContext(
        val resolvedOffer: Oid4vciFacadeResolvedOffer,
        var tokenState: Oid4vciFacadeCredentialTokenState,
        val signer: JwsSigner,
        val clientId: String,
        val keyId: String,
        val httpClient: Oid4vciAsyncHttpClient,
        val redirectUrl: String?,
        var waitingForAuthCode: Oid4vciFacadeWaitingForAuthorizationCode? = null,
    )

    private val sessions = ConcurrentHashMap<String, SessionContext>()

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

    override fun parseOffer(
        credentialOffer: String,
        callback: (Result<ParsedOfferMetadata>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val httpClient = Oid4vciAsyncHttpClient()
                val client = facadeClient("parse-offer-only")
                val offerUrl = normalizeOfferUrl(credentialOffer)
                val resolved = client.resolveOfferUrl(httpClient, offerUrl)
                callback(Result.success(buildParsedOfferMetadata(resolved)))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun acceptOffer(
        credentialOffer: String,
        clientId: String,
        keyId: String,
        didMethod: DidMethod,
        redirectUrl: String?,
        callback: (Result<OfferSession>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val httpClient = Oid4vciAsyncHttpClient()
                val signer = buildJwsSigner(keyId)
                val derivedClientId = derivedClientId(keyId)
                val client = facadeClient(derivedClientId)
                val offerUrl = normalizeOfferUrl(credentialOffer)
                val resolved = client.resolveOfferUrl(httpClient, offerUrl)
                val tokenState = client.acceptOffer(httpClient, resolved)
                val sessionId = UUID.randomUUID().toString()
                sessions[sessionId] = SessionContext(
                    resolvedOffer = resolved,
                    tokenState = tokenState,
                    signer = signer,
                    clientId = derivedClientId,
                    keyId = keyId,
                    httpClient = httpClient,
                    redirectUrl = redirectUrl,
                )
                callback(Result.success(
                    OfferSession(
                        sessionId = sessionId,
                        metadata = buildParsedOfferMetadata(resolved),
                    )
                ))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    override fun continueWithTxCode(
        sessionId: String,
        txCode: String,
        callback: (Result<Oid4vciResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val ctx = sessions[sessionId]
            if (ctx == null) {
                callback(Result.success(Oid4vciError("session not found")))
                return@launch
            }
            val txState = ctx.tokenState as? Oid4vciFacadeCredentialTokenState.RequiresTxCode
            if (txState == null) {
                sessions.remove(sessionId)
                callback(Result.success(Oid4vciError("session not in tx_code state")))
                return@launch
            }
            try {
                val credentialToken = txState.v1.proceed(ctx.httpClient, txCode)
                val credentials = exchangeCredentialWithToken(ctx, credentialToken)
                sessions.remove(sessionId)
                callback(Result.success(Oid4vciSuccess(credentials)))
            } catch (e: Exception) {
                sessions.remove(sessionId)
                callback(Result.success(Oid4vciError(e.message ?: "unknown")))
            }
        }
    }

    override fun buildAuthorizationUrl(
        sessionId: String,
        callback: (Result<String?>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val ctx = sessions[sessionId]
            if (ctx == null) {
                callback(Result.success(null))
                return@launch
            }
            val redirect = ctx.redirectUrl
            if (redirect.isNullOrEmpty()) {
                callback(Result.success(null))
                return@launch
            }
            val authState = ctx.tokenState as? Oid4vciFacadeCredentialTokenState.RequiresAuthorizationCode
            if (authState == null) {
                callback(Result.success(null))
                return@launch
            }
            try {
                val waiting = authState.v1.proceed(ctx.httpClient, redirect)
                ctx.waitingForAuthCode = waiting
                callback(Result.success(waiting.redirectUrl()))
            } catch (e: Exception) {
                callback(Result.success(null))
            }
        }
    }

    override fun continueWithAuthorizationCode(
        sessionId: String,
        code: String,
        callback: (Result<Oid4vciResult>) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val ctx = sessions[sessionId]
            if (ctx == null) {
                callback(Result.success(Oid4vciError("session not found")))
                return@launch
            }
            val waiting = ctx.waitingForAuthCode
            if (waiting == null) {
                sessions.remove(sessionId)
                callback(Result.success(Oid4vciError("session not awaiting authorization code")))
                return@launch
            }
            try {
                val credentialToken = waiting.proceed(ctx.httpClient, code)
                val credentials = exchangeCredentialWithToken(ctx, credentialToken)
                sessions.remove(sessionId)
                callback(Result.success(Oid4vciSuccess(credentials)))
            } catch (e: Exception) {
                sessions.remove(sessionId)
                callback(Result.success(Oid4vciError(e.message ?: "unknown")))
            }
        }
    }

    override fun releaseSession(
        sessionId: String,
        callback: (Result<Unit>) -> Unit
    ) {
        sessions.remove(sessionId)
        callback(Result.success(Unit))
    }

    // — Helpers —

    private fun normalizeOfferUrl(credentialOffer: String): String =
        if (credentialOffer.startsWith("openid-credential-offer://")) credentialOffer
        else "openid-credential-offer://$credentialOffer"

    /// Compatibility facade in Auto mode: prefers the OID4VCI 1.0 (final)
    /// request format and transparently retries with the legacy draft format
    /// when the issuer rejects the v1 credential request with a 400.
    private fun facadeClient(clientId: String): Oid4vciFacadeClient =
        Oid4vciFacadeClient(clientId, Oid4vciCompatibilityMode.AUTO)

    private fun derivedClientId(keyId: String): String {
        val keyManager = KeyManager()
        val jwk = keyManager.getOrInsertJwk(keyId)
        return generateDidJwkUrl(jwk).did().toString()
    }

    private fun buildJwsSigner(keyId: String): JwsSigner {
        val keyManager = KeyManager()
        val jwk = keyManager.getOrInsertJwk(keyId)
        val didUrl = generateDidJwkUrl(jwk)
        jwk.setKid(didUrl.toString())
        return object : JwsSigner {
            override suspend fun fetchInfo(): JwsSignerInfo = jwk.fetchInfo()
            override suspend fun signBytes(signingBytes: ByteArray): ByteArray =
                decodeDerSignature(keyManager.signPayload(keyId, signingBytes)
                    ?: throw IllegalStateException("signing failed for key: $keyId"))
        }
    }

    private fun buildParsedOfferMetadata(resolved: Oid4vciFacadeResolvedOffer): ParsedOfferMetadata {
        val grantType = when (resolved.grantType()) {
            RsGrantType.PRE_AUTH_CODE_NO_TX_CODE -> GrantType.PRE_AUTH_CODE_NO_TX_CODE
            RsGrantType.PRE_AUTH_CODE_WITH_TX_CODE -> GrantType.PRE_AUTH_CODE_WITH_TX_CODE
            RsGrantType.AUTHORIZATION_CODE -> GrantType.AUTHORIZATION_CODE
        }
        return ParsedOfferMetadata(
            issuerId = resolved.credentialIssuer(),
            issuerDisplayName = resolved.issuerDisplayName(),
            credentialConfigurationIds = resolved.credentialConfigurationIds(),
            grantType = grantType,
            txCode = buildTxCodeMetadata(resolved),
        )
    }

    private fun buildTxCodeMetadata(resolved: Oid4vciFacadeResolvedOffer): TxCodeMetadata? {
        val def = resolved.txCodeDefinition() ?: return null
        val inputMode = when (def.inputMode) {
            RsInputMode.TEXT -> TxCodeInputMode.TEXT
            RsInputMode.NUMERIC -> TxCodeInputMode.NUMERIC
        }
        return TxCodeMetadata(
            inputMode = inputMode,
            length = def.length?.toLong(),
            description = def.description,
        )
    }

    /// Exchange every credential in the offer against the token. Loops over
    /// the offer's credential_configuration_ids and issues a fresh nonce + JWT
    /// proof per credential (the protocol requires a unique proof per request).
    private suspend fun exchangeAllCredentials(
        httpClient: Oid4vciAsyncHttpClient,
        clientId: String,
        audience: String,
        signer: JwsSigner,
        token: Oid4vciFacadeCredentialToken,
        configIds: List<String>,
    ): List<IssuedCredential> {
        val result = mutableListOf<IssuedCredential>()
        for (configId in configIds) {
            val nonce = token.getNonce(httpClient)
            val jwt = createJwtProof(clientId, audience, null, nonce, signer)
            val proofs = Proofs.Jwt(listOf(jwt))
            val response = token.exchangeCredential(
                httpClient,
                CredentialOrConfigurationId.Configuration(configId),
                proofs,
            )
            when (response) {
                is CredentialResponse.Immediate -> {
                    result.addAll(response.v1.credentials.map { cred ->
                        val formatString = when (cred.format) {
                            is RsCredentialFormat.MsoMdoc -> "mso_mdoc"
                            is RsCredentialFormat.JwtVcJson -> "jwt_vc_json"
                            is RsCredentialFormat.JwtVcJsonLd -> "jwt_vc_json-ld"
                            is RsCredentialFormat.LdpVc -> "ldp_vc"
                            is RsCredentialFormat.Vcdm2SdJwt -> "vc+sd-jwt"
                            is RsCredentialFormat.DcSdJwt -> "dc+sd-jwt"
                            is RsCredentialFormat.Cwt -> "cwt"
                            is RsCredentialFormat.OpticalBarcodeCredential -> "optical_barcode_credential"
                            is RsCredentialFormat.Other -> (cred.format as RsCredentialFormat.Other).v1
                        }
                        IssuedCredential(payload = cred.payload.toString(Charsets.UTF_8), format = formatString)
                    })
                }
                is CredentialResponse.Deferred -> throw IllegalStateException("Deferred credentials not supported")
            }
        }
        return result
    }

    private suspend fun exchangeCredentialWithToken(
        ctx: SessionContext,
        token: Oid4vciFacadeCredentialToken,
    ): List<IssuedCredential> = exchangeAllCredentials(
        httpClient = ctx.httpClient,
        clientId = ctx.clientId,
        audience = ctx.resolvedOffer.credentialIssuer(),
        signer = ctx.signer,
        token = token,
        configIds = ctx.resolvedOffer.credentialConfigurationIds(),
    )

    private suspend fun performIssuance(
        credentialOffer: String,
        keyId: String
    ): Oid4vciResult {
        val httpClient = Oid4vciAsyncHttpClient()
        val signer = buildJwsSigner(keyId)
        val derivedClientId = derivedClientId(keyId)
        val oid4vciClient = facadeClient(derivedClientId)
        val offerUrl = normalizeOfferUrl(credentialOffer)
        val offer = oid4vciClient.resolveOfferUrl(httpClient, offerUrl)
        val credentialIssuer = offer.credentialIssuer()

        return when (val state = oid4vciClient.acceptOffer(httpClient, offer)) {
            is Oid4vciFacadeCredentialTokenState.RequiresAuthorizationCode -> {
                Oid4vciError(message = "Authorization Code Grant not supported")
            }
            is Oid4vciFacadeCredentialTokenState.RequiresTxCode -> {
                Oid4vciError(message = "Transaction Code not supported")
            }
            is Oid4vciFacadeCredentialTokenState.Ready -> {
                val credentialToken = state.v1
                val issuedCredentials = exchangeAllCredentials(
                    httpClient = httpClient,
                    clientId = derivedClientId,
                    audience = credentialIssuer,
                    signer = signer,
                    token = credentialToken,
                    configIds = offer.credentialConfigurationIds(),
                )
                Oid4vciSuccess(credentials = issuedCredentials)
            }
        }
    }
}
