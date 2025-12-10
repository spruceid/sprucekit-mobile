import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

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
                    clientId: clientId,
                    redirectUrl: redirectUrl,
                    keyId: keyId,
                    didMethod: didMethod,
                    contextMap: contextMap
                )
                completion(.success(result))
            } catch {
                completion(.success(Oid4vciError(message: error.localizedDescription)))
            }
        }
    }

    private func performIssuance(
        credentialOffer: String,
        clientId: String,
        redirectUrl: String,
        keyId: String,
        didMethod: DidMethod,
        contextMap: [String: String]?
    ) async throws -> Oid4vciResult {

        // Create OID4VCI session with async HTTP client
        let client = Oid4vciAsyncHttpClient()
        let oid4vciSession = SpruceIDMobileSdkRs.Oid4vci.newWithAsyncClient(client: client)

        // Initiate with offer
        try await oid4vciSession.initiateWithOffer(
            credentialOffer: credentialOffer,
            clientId: clientId,
            redirectUrl: redirectUrl
        )

        // Exchange token to get nonce
        let nonce = try await oid4vciSession.exchangeToken()

        // Get metadata
        let metadata = try oid4vciSession.getMetadata()

        // Ensure signing key exists
        if !KeyManager.keyExists(id: keyId) {
            _ = KeyManager.generateSigningKey(id: keyId)
        }

        // Get JWK
        guard let jwk = KeyManager.getJwk(id: keyId) else {
            return Oid4vciError(message: "Failed to get JWK for key: \(keyId)")
        }

        // Convert DID method to native DidMethod type
        let nativeDidMethod: SpruceIDMobileSdkRs.DidMethod
        switch didMethod {
        case .jwk:
            nativeDidMethod = .jwk
        case .key:
            nativeDidMethod = .key
        }

        // Generate Proof of Possession
        let signingInput = try await SpruceIDMobileSdkRs.generatePopPrepare(
            audience: metadata.issuer(),
            nonce: nonce,
            didMethod: nativeDidMethod,
            publicJwk: jwk,
            durationInSecs: nil
        )

        guard let signature = KeyManager.signPayload(id: keyId, payload: [UInt8](signingInput)) else {
            return Oid4vciError(message: "Failed to sign payload")
        }

        let pop = try SpruceIDMobileSdkRs.generatePopComplete(
            signingInput: signingInput,
            signatureDer: Data(signature)
        )

        // Set context map if provided
        if let contextMap = contextMap {
            try oid4vciSession.setContextMap(values: contextMap)
        }

        // Exchange credential
        let credentials = try await oid4vciSession.exchangeCredential(
            proofsOfPossession: [pop],
            options: SpruceIDMobileSdkRs.Oid4vciExchangeOptions(verifyAfterExchange: false)
        )

        // Convert to issued credentials
        let issuedCredentials = credentials.map { cred in
            IssuedCredential(
                payload: String(decoding: cred.payload, as: UTF8.self),
                format: formatToString(cred.format)
            )
        }

        return Oid4vciSuccess(credentials: issuedCredentials)
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
        case .cwt:
            return "cwt"
        case .other(let value):
            return value
        }
    }
}
