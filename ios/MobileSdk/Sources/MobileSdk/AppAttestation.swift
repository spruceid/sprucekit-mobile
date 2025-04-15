import CryptoKit
import DeviceCheck
import Foundation

/// A class that handles App Attestation functionality for iOS devices
public class AppAttestation {

    /// Error types that can occur during attestation
    public enum AttestationError: Error {
        case attestationNotSupported
        case invalidJWK
        case encodingError(String)
        case jsonSerializationError(String)
    }

    private let service: DCAppAttestService

    public init() {
        self.service = DCAppAttestService.shared
    }

    /// Generates a new attestation key using DCAppAttestService
    /// - Returns: A string containing the generated key identifier
    /// - Throws: AttestationError.attestationNotSupported if the device doesn't support App Attestation
    public func generateAttestationKey() async throws -> String {
        guard service.isSupported else {
            throw AttestationError.attestationNotSupported
        }

        return try await service.generateKey()
    }

    /// Generates an assertion for a given key ID and client data
    /// - Parameters:
    ///   - id: The key identifier to generate assertion for
    ///   - clientData: The client data to be hashed and used in assertion
    /// - Returns: A base64 encoded string containing the assertion
    /// - Throws: AttestationError.attestationNotSupported or AttestationError.encodingError
    public func generateAssertion(id: String, clientData: Data) async throws
        -> String {
        guard service.isSupported else {
            throw AttestationError.attestationNotSupported
        }

        let clientDataHash = SHA256.hash(data: clientData)

        do {
            let assertion = try await service.generateAssertion(
                id,
                clientDataHash: Data(clientDataHash)
            )
            if assertion.isEmpty {
                throw AttestationError.encodingError("assertion was empty")
            }
            return assertion.base64EncodedString()
        } catch {
            throw AttestationError.encodingError(error.localizedDescription)
        }
    }

    /// Generates an attestation for a key with the provided nonce
    /// - Parameters:
    ///   - keyId: The key identifier to attest
    ///   - nonce: The nonce string to be used in attestation
    /// - Returns: A base64 encoded string containing the attestation data
    /// - Throws: AttestationError.attestationNotSupported or AttestationError.encodingError
    public func generateAttestation(keyId: String, nonce: String) async throws
        -> String {
        guard service.isSupported else {
            throw AttestationError.attestationNotSupported
        }

        guard let clientData = nonce.data(using: .utf8) else {
            throw AttestationError.encodingError(
                "failed to convert nonce to utf8 bytes"
            )
        }

        let clientDataHash = SHA256.hash(data: clientData)

        do {
            let attestation = try await service.attestKey(
                keyId,
                clientDataHash: Data(clientDataHash)
            )
            if attestation.isEmpty {
                throw AttestationError.encodingError("attestation was empty")
            }
            return attestation.base64EncodedString()
        } catch {
            throw AttestationError.encodingError(error.localizedDescription)
        }
    }

    /// Checks if the device supports App Attestation
    /// - Returns: Boolean indicating if attestation is supported on the current device
    public func isAttestationSupported() -> Bool {
        return service.isSupported
    }

    /// Performs a complete attestation flow with the provided JWK and nonce
    /// - Parameters:
    ///   - jwk: The public JWK of the device key
    ///   - nonce: The nonce retrieved from the verification service
    /// - Returns: A JSON string containing the complete attestation data including keyId, 
    ///   attestation, assertion and clientData
    /// - Throws: Various AttestationError cases if any step of the attestation process fails
    public func appAttest(jwk: String, nonce: String) async throws -> String {
        guard service.isSupported else {
            throw AttestationError.attestationNotSupported
        }
        // Validate and prepare client data
        let clientData = try prepareClientData(jwk: jwk, nonce: nonce)
        let clientDataString = clientData.base64EncodedString()
        // Generate key and attestation
        let keyId = try await generateAttestationKey()
        let attestation = try await generateAttestation(
            keyId: keyId,
            nonce: nonce
        )
        let assertion = try await generateAssertion(
            id: keyId,
            clientData: clientData
        )
        let payload = try preparePayload(
            keyId: keyId,
            attestation: attestation,
            assertion: assertion,
            clientData: clientDataString

        )
        return payload
    }

    private func prepareClientData(jwk: String, nonce: String) throws -> Data {
        guard let jwkData = jwk.data(using: .utf8) else {
            throw AttestationError.invalidJWK
        }

        guard
            let jwkJson = try? JSONSerialization.jsonObject(with: jwkData)
                as? [String: Any]
        else {
            throw AttestationError.jsonSerializationError("Invalid JWK format")
        }

        let clientDataObject: [String: Any] = [
            "nonce": nonce,
            "jwk": jwkJson
        ]

        return try JSONSerialization.data(withJSONObject: clientDataObject)
    }

    private func preparePayload(
        keyId: String,
        attestation: String,
        assertion: String,
        clientData: String
    ) throws -> String {
        let payloadObject: [String: Any] = [
            "keyId": keyId,
            "keyAttestation": attestation,
            "keyAssertion": assertion,
            "clientData": clientData
        ]

        let payloadData = try JSONSerialization.data(
            withJSONObject: payloadObject
        )
        guard let payloadString = String(data: payloadData, encoding: .utf8)
        else {
            throw AttestationError.encodingError(
                "Failed to encode payload as string"
            )
        }

        return payloadString
    }
}
