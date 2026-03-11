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

/// OID4VCI Pigeon Adapter for iOS
///
/// Handles OpenID for Verifiable Credential Issuance flow
class Oid4vciAdapter: Oid4vci {

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

    private func performIssuance(
        credentialOffer: String,
        keyId: String
    ) async throws -> Oid4vciResult {

        // Setup HTTP client
        let httpClient = Oid4vciAsyncHttpClient()

        // Setup key and signer
        let jwk = KeyManager.getOrInsertJwk(id: keyId)
        let didUrl = generateDidJwkUrl(jwk: jwk)
        jwk.setKid(kid: didUrl.description)
        let signer = Oid4vciJwsSigner(keyId: keyId, jwk: jwk)

        // Derive client ID from key's DID
        let derivedClientId = didUrl.did().description
        let oid4vciClient = Oid4vciClient(clientId: derivedClientId)

        // Resolve offer URL
        let offerUrl = credentialOffer.starts(with: "openid-credential-offer://")
            ? credentialOffer
            : "openid-credential-offer://\(credentialOffer)"

        let offer = try await oid4vciClient.resolveOfferUrl(
            httpClient: httpClient,
            credentialOfferUrl: offerUrl
        )
        let credentialIssuer = offer.credentialIssuer()

        // Accept offer
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

            // Generate Proof of Possession
            let nonce = try await credentialToken.getNonce(httpClient: httpClient)
            let jwt = try await createJwtProof(
                issuer: derivedClientId,
                audience: credentialIssuer,
                expireInSecs: nil,
                nonce: nonce,
                signer: signer
            )
            let proofs = Proofs.jwt([jwt])

            // Exchange credential
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
                        payload: String(decoding: cred.payload, as: UTF8.self),
                        format: formatToString(cred.format)
                    )
                }
                return Oid4vciSuccess(credentials: issuedCredentials)
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
}
