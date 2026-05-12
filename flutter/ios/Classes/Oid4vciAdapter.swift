import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// JWS Signer implementation using KeyManager for OID4VCI proof of possession
class Oid4vciJwsSigner: JwsSigner, @unchecked Sendable {
    private let keyId: String
    private let jwk: Jwk

    init(keyId: String, jwk: Jwk) {
        self.keyId = keyId
        self.jwk = jwk
    }

    func fetchInfo() async throws -> JwsSignerInfo {
        return try await jwk.fetchInfo()
    }

    func signBytes(signingBytes: Data) async throws -> Data {
        guard let signature = KeyManager.signPayload(id: keyId, payload: [UInt8](signingBytes)) else {
            throw Oid4vciAdapterError.signingFailed
        }
        return try decodeDerSignature(signatureDer: Data(signature))
    }
}

/// Error types for OID4VCI adapter
enum Oid4vciAdapterError: Error {
    case signingFailed
}

// MARK: - Session Registry

private actor Oid4vciSessionRegistry {
    struct SessionContext {
        let resolvedOffer: ResolvedCredentialOffer
        var tokenState: CredentialTokenState
        let signer: Oid4vciJwsSigner
        let clientId: String
        let keyId: String
        let httpClient: Oid4vciAsyncHttpClient
    }

    private var sessions: [String: SessionContext] = [:]

    func insert(id: String, ctx: SessionContext) { sessions[id] = ctx }
    func get(id: String) -> SessionContext? { sessions[id] }
    func remove(id: String) { sessions.removeValue(forKey: id) }
}

// MARK: - Adapter

/// OID4VCI Pigeon Adapter for iOS
///
/// Handles OpenID for Verifiable Credential Issuance flow
class Oid4vciAdapter: Oid4vci {

    private let registry = Oid4vciSessionRegistry()

    // MARK: Protocol methods

    func parseOffer(
        credentialOffer: String,
        completion: @escaping (Result<ParsedOfferMetadata, Error>) -> Void
    ) {
        Task {
            do {
                let httpClient = Oid4vciAsyncHttpClient()
                let client = Oid4vciClient(clientId: "parse-offer-only")
                let offerUrl = normalizeOfferUrl(credentialOffer)
                let resolved = try await client.resolveOfferUrl(
                    httpClient: httpClient,
                    credentialOfferUrl: offerUrl
                )
                completion(.success(buildParsedOfferMetadata(resolved: resolved)))
            } catch {
                completion(.failure(error))
            }
        }
    }

    func runIssuance(
        credentialOffer: String,
        clientId: String,
        redirectUrl: String,
        keyId: String,
        didMethod: DidMethod,
        contextMap: [String: String]?,
        completion: @escaping (Result<Oid4vciResult, any Error>) -> Void
    ) {
        Task {
            do {
                let result = try await performIssuance(
                    credentialOffer: credentialOffer,
                    keyId: keyId
                )
                completion(.success(result))
            } catch {
                completion(.success(Oid4vciError(message: error.localizedDescription)))
            }
        }
    }

    func acceptOffer(
        credentialOffer: String,
        clientId: String,
        keyId: String,
        didMethod: DidMethod,
        completion: @escaping (Result<OfferSession, Error>) -> Void
    ) {
        Task {
            do {
                let httpClient = Oid4vciAsyncHttpClient()
                let signer = buildJwsSigner(keyId: keyId)
                let derivedClientId = derivedClientId(keyId: keyId)
                let oid4vciClient = Oid4vciClient(clientId: derivedClientId)
                let offerUrl = normalizeOfferUrl(credentialOffer)
                let resolved = try await oid4vciClient.resolveOfferUrl(
                    httpClient: httpClient,
                    credentialOfferUrl: offerUrl
                )
                let tokenState = try await oid4vciClient.acceptOffer(
                    httpClient: httpClient,
                    credentialOffer: resolved
                )
                let sessionId = UUID().uuidString
                let ctx = Oid4vciSessionRegistry.SessionContext(
                    resolvedOffer: resolved,
                    tokenState: tokenState,
                    signer: signer,
                    clientId: derivedClientId,
                    keyId: keyId,
                    httpClient: httpClient
                )
                await registry.insert(id: sessionId, ctx: ctx)
                completion(.success(OfferSession(
                    sessionId: sessionId,
                    metadata: buildParsedOfferMetadata(resolved: resolved)
                )))
            } catch {
                completion(.failure(error))
            }
        }
    }

    func continueWithTxCode(
        sessionId: String,
        txCode: String,
        completion: @escaping (Result<Oid4vciResult, Error>) -> Void
    ) {
        Task {
            guard let ctx = await registry.get(id: sessionId) else {
                completion(.success(Oid4vciError(message: "session not found")))
                return
            }
            guard case .requiresTxCode(let txState) = ctx.tokenState else {
                await registry.remove(id: sessionId)
                completion(.success(Oid4vciError(message: "session not in tx_code state")))
                return
            }
            do {
                let token = try await txState.proceed(httpClient: ctx.httpClient, txCode: txCode)
                let credentials = try await exchangeCredentialWithToken(ctx: ctx, token: token)
                await registry.remove(id: sessionId)
                completion(.success(Oid4vciSuccess(credentials: credentials)))
            } catch {
                await registry.remove(id: sessionId)
                completion(.success(Oid4vciError(message: error.localizedDescription)))
            }
        }
    }

    func releaseSession(
        sessionId: String,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        Task {
            await registry.remove(id: sessionId)
            completion(.success(()))
        }
    }

    // MARK: - Helpers

    private func normalizeOfferUrl(_ credentialOffer: String) -> String {
        credentialOffer.starts(with: "openid-credential-offer://")
            ? credentialOffer
            : "openid-credential-offer://\(credentialOffer)"
    }

    private func buildJwsSigner(keyId: String) -> Oid4vciJwsSigner {
        let jwk = KeyManager.getOrInsertJwk(id: keyId)
        let didUrl = generateDidJwkUrl(jwk: jwk)
        jwk.setKid(kid: didUrl.description)
        return Oid4vciJwsSigner(keyId: keyId, jwk: jwk)
    }

    private func derivedClientId(keyId: String) -> String {
        let jwk = KeyManager.getOrInsertJwk(id: keyId)
        let didUrl = generateDidJwkUrl(jwk: jwk)
        return didUrl.did().description
    }

    private func buildParsedOfferMetadata(resolved: ResolvedCredentialOffer) -> ParsedOfferMetadata {
        let rsGrantType: SpruceIDMobileSdkRs.GrantType = resolved.grantType()
        let grantType: GrantType
        switch rsGrantType {
        case .preAuthCodeNoTxCode:
            grantType = .preAuthCodeNoTxCode
        case .preAuthCodeWithTxCode:
            grantType = .preAuthCodeWithTxCode
        case .authorizationCode:
            grantType = .authorizationCode
        }
        return ParsedOfferMetadata(
            issuerId: resolved.credentialIssuer(),
            issuerDisplayName: resolved.issuerDisplayName(),
            credentialConfigurationIds: resolved.credentialConfigurationIds(),
            grantType: grantType,
            txCode: buildTxCodeMetadata(resolved: resolved)
        )
    }

    private func buildTxCodeMetadata(resolved: ResolvedCredentialOffer) -> TxCodeMetadata? {
        guard let def = resolved.txCodeDefinition() else { return nil }
        let inputMode: TxCodeInputMode
        switch def.inputMode {
        case .numeric:
            inputMode = .numeric
        case .text:
            inputMode = .text
        }
        return TxCodeMetadata(
            inputMode: inputMode,
            length: def.length.map { Int64($0) },
            description: def.description
        )
    }

    private func exchangeCredentialWithToken(
        ctx: Oid4vciSessionRegistry.SessionContext,
        token: CredentialToken
    ) async throws -> [IssuedCredential] {
        let credentialId = try token.defaultCredentialId()
        let nonce = try await token.getNonce(httpClient: ctx.httpClient)
        let jwt = try await createJwtProof(
            issuer: ctx.clientId,
            audience: ctx.resolvedOffer.credentialIssuer(),
            expireInSecs: nil,
            nonce: nonce,
            signer: ctx.signer
        )
        let proofs = Proofs.jwt([jwt])
        let response = try await Oid4vciClient(clientId: ctx.clientId)
            .exchangeCredential(httpClient: ctx.httpClient, token: token, credential: credentialId, proofs: proofs)
        switch response {
        case .deferred:
            throw NSError(
                domain: "OID4VCI",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Deferred credentials not supported"]
            )
        case .immediate(let immediateResponse):
            return immediateResponse.credentials.map { cred in
                IssuedCredential(
                    payload: String(decoding: Data(cred.payload), as: UTF8.self),
                    format: formatToString(cred.format)
                )
            }
        }
    }

    /// Convert native CredentialFormat enum to string for Pigeon API
    private func formatToString(_ format: SpruceIDMobileSdkRs.CredentialFormat) -> String {
        switch format {
        case .msoMdoc:
            return "mso_mdoc"
        case .jwtVcJson:
            return "jwt_vc_json"
        case .jwtVcJsonLd:
            return "jwt_vc_json-ld"
        case .ldpVc:
            return "ldp_vc"
        case .vcdm2SdJwt:
            return "vc+sd-jwt"
        case .dcSdJwt:
            return "dc+sd-jwt"
        case .cwt:
            return "cwt"
        case .other(let value):
            return value
        }
    }

    // MARK: - Legacy (slice-1) full-flow path

    private func performIssuance(
        credentialOffer: String,
        keyId: String
    ) async throws -> Oid4vciResult {
        let httpClient = Oid4vciAsyncHttpClient()
        let signer = buildJwsSigner(keyId: keyId)
        let derivedClientId = derivedClientId(keyId: keyId)
        let oid4vciClient = Oid4vciClient(clientId: derivedClientId)
        let offerUrl = normalizeOfferUrl(credentialOffer)
        let offer = try await oid4vciClient.resolveOfferUrl(
            httpClient: httpClient,
            credentialOfferUrl: offerUrl
        )
        let credentialIssuer = offer.credentialIssuer()
        let state = try await oid4vciClient.acceptOffer(
            httpClient: httpClient,
            credentialOffer: offer
        )

        switch state {
        case .requiresAuthorizationCode(_):
            return Oid4vciError(message: "Authorization Code Grant not supported")
        case .requiresTxCode(_):
            return Oid4vciError(message: "Transaction Code not supported")
        case .ready(let credentialToken):
            let credentialId = try credentialToken.defaultCredentialId()
            let nonce = try await credentialToken.getNonce(httpClient: httpClient)
            let jwt = try await createJwtProof(
                issuer: derivedClientId,
                audience: credentialIssuer,
                expireInSecs: nil,
                nonce: nonce,
                signer: signer
            )
            let proofs = Proofs.jwt([jwt])
            let response = try await oid4vciClient.exchangeCredential(
                httpClient: httpClient,
                token: credentialToken,
                credential: credentialId,
                proofs: proofs
            )
            switch response {
            case .deferred(_):
                return Oid4vciError(message: "Deferred credentials not supported")
            case .immediate(let immediateResponse):
                let issuedCredentials = immediateResponse.credentials.map { cred in
                    IssuedCredential(
                        payload: String(decoding: Data(cred.payload), as: UTF8.self),
                        format: formatToString(cred.format)
                    )
                }
                return Oid4vciSuccess(credentials: issuedCredentials)
            }
        }
    }
}
