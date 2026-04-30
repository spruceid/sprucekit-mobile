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

    /// Returns demo PDF supplements:
    ///   • QR — a real, verifiable **SD-JWT VP** with `portrait` selectively
    ///     hidden, generated end-to-end on this device (test fixture issuer
    ///     → VP token → bytes).
    ///   • PDF-417 — AAMVA-style mock string (real AAMVA encoder integration
    ///     handled by the `generateAamvaPdf417Bytes` PR; not yet wired here).
    ///
    /// ## Swap to a real CA DMV credential
    /// Replace the `generateTestMdlSdJwtCompact()` call below with the
    /// SD-JWT compact string fetched from the wallet's stored credentials
    /// (e.g. once the Alice/Tiago microservice PR ships and the wallet
    /// receives `format == "vc+sd-jwt"` from the OID4VCI `/credential`
    /// endpoint). Everything downstream stays identical.
    ///
    /// See `vcdm2_sd_jwt.rs::generate_test_mdl_sd_jwt` for the full swap
    /// recipe.
    func getDemoSupplements() async throws -> [PdfSupplement] {
        // 1. Get a self-signed test SD-JWT (REPLACE WITH REAL CREDENTIAL).
        let sdJwtCompact = await generateTestMdlSdJwtCompact()

        // 2. Parse into a ParsedCredential the SDK can work with.
        let sdJwt = try Vcdm2SdJwt.newFromCompactSdJwt(input: sdJwtCompact)
        let credential = ParsedCredential.newSdJwt(sdJwtVc: sdJwt)

        // 3. Generate the SD-JWT VP that hides `portrait`.
        let vpParams = VpTokenParams(
            disclosure: .hideOnly(fields: ["portrait"]),
            audience: "https://demo.spruceid.com",
            nonce: nil
        )
        let vpBytes = try await generateCredentialVpToken(
            credential: credential,
            params: vpParams
        )

        // 4. Compress for QR numeric-mode encoding. The raw SD-JWT VP is
        //    too large for QR byte mode (~2.95 KB cap @ V40 L-EC); the
        //    Colorado deflate+base10+"9"-prefix scheme produces an
        //    all-digit payload that QR auto-encodes in numeric mode
        //    (~7089 digits cap), where it fits comfortably.
        //    Verifier (`verifySdJwtVp`) auto-detects the leading `9` and
        //    decompresses before signature checking.
        let qrBytes = try compressVpForQr(vpToken: Data(vpBytes))

        // 4. PDF-417 payload — still a mock AAMVA-style string. The actual
        //    AAMVA encoder (generateAamvaPdf417Bytes) is on a parallel PR.
        let pdf417Payload = "DAQ DL-123456789\nDCS Doe\nDCT John\nDBB 01151990\nDBA 01152029"

        return [
            .barcode(data: Data(qrBytes), barcodeType: .qrCode),
            .barcode(data: Data(pdf417Payload.utf8), barcodeType: .pdf417),
        ]
    }
}
