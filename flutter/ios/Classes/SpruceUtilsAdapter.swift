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
