import Flutter
import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Adapter implementing the SpruceUtils Pigeon protocol
class SpruceUtilsAdapter: NSObject, SpruceUtils {
    private let credentialPackAdapter: CredentialPackAdapter

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
        super.init()
    }

    func generateCredentialPdf(
        rawMdoc: String,
        supplements: [PdfSupplement],
        completion: @escaping (Result<FlutterStandardTypedData, Error>) -> Void
    ) {
        Task {
            do {
                // rawMdoc is standard Base64-encoded CBOR Document bytes
                // (from parsedCredential.intoGenericForm().payload)
                guard let documentBytes = Data(base64Encoded: rawMdoc) else {
                    completion(.failure(NSError(
                        domain: "SpruceUtilsAdapter",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to decode base64 rawMdoc"]
                    )))
                    return
                }
                let mdoc = try Mdoc.fromCborEncodedDocument(
                    cborEncodedDocument: documentBytes,
                    keyAlias: "pdf"
                )
                let credential = ParsedCredential.newMsoMdoc(mdoc: mdoc)

                // Convert Pigeon supplements to Rust PdfSupplement
                let rustSupplements: [SpruceIDMobileSdkRs.PdfSupplement] = supplements.compactMap { sup -> SpruceIDMobileSdkRs.PdfSupplement? in
                    switch sup.type {
                    case .barcode:
                        guard let pigeonData = sup.data, let barcodeType = sup.barcodeType else {
                            return nil
                        }
                        let data = pigeonData.data  // FlutterStandardTypedData -> Data
                        let rustBarcodeType: SpruceIDMobileSdkRs.BarcodeType = {
                            switch barcodeType {
                            case .qrCode: return .qrCode
                            case .pdf417: return .pdf417
                            }
                        }()
                        return .barcode(data: data, barcodeType: rustBarcodeType)
                    }
                }

                let pdfBytes = try SpruceIDMobileSdkRs.generateCredentialPdf(
                    credential: credential,
                    supplements: rustSupplements
                )
                completion(.success(FlutterStandardTypedData(bytes: Data(pdfBytes))))
            } catch {
                completion(.failure(error))
            }
        }
    }

    /// Shared helper: parses a compact SD-JWT string and generates a raw VP Token byte array.
    /// Both `generateCredentialVpToken` and `generateCompressedVpToken` use this logic.
    private func buildVpTokenBytes(rawSdJwt: String, params: VpTokenParams) async throws -> [UInt8] {
        // Parse the compact SD-JWT into a ParsedCredential.
        let sdJwt = try SpruceIDMobileSdkRs.Vcdm2SdJwt.newFromCompactSdJwt(input: rawSdJwt)
        let credential = SpruceIDMobileSdkRs.ParsedCredential.newSdJwt(sdJwtVc: sdJwt)

        // Convert Pigeon DisclosureSelection -> Rust DisclosureSelection
        let rustDisclosure: SpruceIDMobileSdkRs.DisclosureSelection
        switch params.disclosure.type {
        case .hideOnly:
            rustDisclosure = .hideOnly(fields: params.disclosure.fields)
        case .selectOnly:
            rustDisclosure = .selectOnly(fields: params.disclosure.fields)
        }

        let rustParams = SpruceIDMobileSdkRs.VpTokenParams(
            disclosure: rustDisclosure,
            audience: params.audience,
            nonce: params.nonce
        )

        return try await SpruceIDMobileSdkRs.generateCredentialVpToken(
            credential: credential,
            params: rustParams
        )
    }

    func generateCredentialVpToken(
        rawSdJwt: String,
        params: VpTokenParams,
        completion: @escaping (Result<FlutterStandardTypedData, Error>) -> Void
    ) {
        Task {
            do {
                let bytes = try await buildVpTokenBytes(rawSdJwt: rawSdJwt, params: params)
                completion(.success(FlutterStandardTypedData(bytes: Data(bytes))))
            } catch {
                completion(.failure(error))
            }
        }
    }

    func generateCompressedVpToken(
        rawSdJwt: String,
        params: VpTokenParams,
        completion: @escaping (Result<FlutterStandardTypedData, Error>) -> Void
    ) {
        Task {
            do {
                let vpBytes = try await buildVpTokenBytes(rawSdJwt: rawSdJwt, params: params)
                // deflate + base10 + "9"-prefix compression so the bytes fit a QR numeric-mode payload.
                let compressed = try SpruceIDMobileSdkRs.compressVpForQr(vpToken: Data(vpBytes))
                completion(.success(FlutterStandardTypedData(bytes: compressed)))
            } catch {
                completion(.failure(error))
            }
        }
    }

    func generateTestMdlSdJwtCompact(
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        Task {
            let compact = await SpruceIDMobileSdkRs.generateTestMdlSdJwtCompact()
            completion(.success(compact))
        }
    }

    func verifySdJwtVp(
        input: String,
        completion: @escaping (Result<Void, Error>) -> Void
    ) {
        Task {
            do {
                try await SpruceIDMobileSdkRs.verifySdJwtVp(input: input)
                completion(.success(()))
            } catch {
                completion(.failure(error))
            }
        }
    }

    func decompressVpFromQr(
        qrPayload: FlutterStandardTypedData,
        completion: @escaping (Result<FlutterStandardTypedData, Error>) -> Void
    ) {
        do {
            let bytes = try SpruceIDMobileSdkRs.decompressVpFromQr(qrPayload: qrPayload.data)
            completion(.success(FlutterStandardTypedData(bytes: bytes)))
        } catch {
            completion(.failure(error))
        }
    }

    func generateMockMdl(
        keyAlias: String?,
        completion: @escaping (Result<GenerateMockMdlResult, Error>) -> Void
    ) {
        let alias = keyAlias ?? "testMdl"

        Task {
            do {
                // Generate or retrieve the signing key
                if !KeyManager.keyExists(id: alias) {
                    _ = KeyManager.generateSigningKey(id: alias)
                }

                // Generate the test mDL
                let mdl = try generateTestMdl(keyManager: KeyManager(), keyAlias: alias)

                // Create a new CredentialPack and add the mDL
                let packId = try credentialPackAdapter.createPack()
                guard let pack = credentialPackAdapter.getNativePack(packId: packId) else {
                    completion(.success(GenerateMockMdlError(message: "Failed to create credential pack")))
                    return
                }

                // Get the raw credential bytes for storage
                let parsedCredential = ParsedCredential.newMsoMdoc(mdoc: mdl)
                let genericCredential = try parsedCredential.intoGenericForm()
                let rawCredentialBase64 = genericCredential.payload.base64EncodedString()

                // Add the mDL to the pack (also registers with ID Provider on iOS 26+)
                let credentials = try await pack.addMDoc(mdoc: mdl)
                guard let credential = credentials.first else {
                    completion(.success(GenerateMockMdlError(message: "Failed to add mDL to pack")))
                    return
                }

                completion(.success(GenerateMockMdlSuccess(
                    packId: packId,
                    credentialId: credential.id(),
                    rawCredential: rawCredentialBase64,
                    keyAlias: alias
                )))
            } catch {
                completion(.success(GenerateMockMdlError(message: "Failed to generate mock mDL: \(error.localizedDescription)")))
            }
        }
    }

}
