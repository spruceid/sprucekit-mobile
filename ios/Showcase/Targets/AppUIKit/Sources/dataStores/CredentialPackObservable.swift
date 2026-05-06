import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

class CredentialPackObservable: ObservableObject {
    @Published var credentialPacks: [CredentialPack]
    let storageManager: StorageManager

    init(appGroupId: String?, credentialPacks: [CredentialPack] = []) {
        let bundle = Bundle.main
        self.storageManager = StorageManager(
            appGroupId: appGroupId)
        self.credentialPacks = credentialPacks
    }
    
    public func registerUnregisteredIDProviderDocuments() async throws {
        for credentialPack in credentialPacks {
            try await credentialPack.registerUnregisteredIDProviderDocuments()
        }
    }

    @MainActor func loadAndUpdateAll() async throws -> [CredentialPack] {
        let credentialPacks = try await CredentialPack.loadAll(
            storageManager: storageManager)
        updateAll(credentialPacks: credentialPacks)
        return credentialPacks
    }

    func updateAll(credentialPacks: [CredentialPack]) {
        self.credentialPacks = credentialPacks
    }

    @MainActor func add(credentialPack: CredentialPack) async throws {
        try await credentialPack.save(storageManager: storageManager)
        self.credentialPacks.append(credentialPack)
    }

    func delete(credentialPack: CredentialPack) async throws {
        try await credentialPack.remove(storageManager: storageManager)
        self.credentialPacks.removeAll { credPack in
            credPack.id.uuidString == credentialPack.id.uuidString
        }
    }

    func getById(credentialPackId: String) -> CredentialPack? {
        return credentialPacks.first { credentialPack in
            credentialPack.id.uuidString == credentialPackId
        }
    }

    /// Returns demo PDF supplements: a single
    /// `PdfSupplement.opticalBarcodeCredential` carrying a freshly-signed test
    /// **W3C VCB** (OpticalBarcodeCredential JSON-LD).  The SDK's
    /// `generateCredentialPdf` will CBOR-LD encode it, embed it as the ZZA
    /// field of an AAMVA ZZ subfile alongside the DL subfile, and render the
    /// resulting PDF-417 into the PDF.
    ///
    /// ## Swap to a real CA DMV VCB
    /// Replace the `generateTestOpticalBarcodeCredential()` call with the
    /// JSON-LD VCB fetched from the wallet's stored credentials, once the
    /// DMV microservice issues VCBs alongside mDLs. Everything downstream
    /// stays identical — the SDK doesn't care whether the JSON-LD came from
    /// a test fixture or a live issuer.
    ///
    /// QR section is intentionally omitted in this demo.  The
    /// `BarcodeType.qrCode` primitive remains available for non-mDL flows.
    func getDemoSupplements(for _: ParsedCredential) async throws -> [PdfSupplement] {
        // Generate a self-signed test VCB (REPLACE WITH REAL CREDENTIAL).
        let jsonld = try await generateTestOpticalBarcodeCredential()

        // Wrap as an OpticalBarcodeCredential ParsedCredential.
        let vcbInner = try OpticalBarcodeCred(jsonld: jsonld)
        let vcbCredential = ParsedCredential.newOpticalBarcodeCredential(cred: vcbInner)

        return [.opticalBarcodeCredential(credential: vcbCredential)]
    }
}
