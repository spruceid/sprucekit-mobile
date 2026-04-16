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

    /// Returns demo PDF supplements with mock barcode data.
    /// In production, QR would be a VP Token and PDF-417 would be AAMVA data.
    func getDemoSupplements() -> [PdfSupplement] {
        let qrPayload = #"{"type":"mDL","source":"SpruceKit Showcase"}"#
        let pdf417Payload = "DAQ DL-123456789\nDCS Doe\nDCT John\nDBB 01151990\nDBA 01152029"
        return [
            .barcode(
                data: Data(qrPayload.utf8),
                barcodeType: .qrCode
            ),
            .barcode(
                data: Data(pdf417Payload.utf8),
                barcodeType: .pdf417
            )
        ]
    }
}
