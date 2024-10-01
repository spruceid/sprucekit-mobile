import Foundation

public class CredentialStore {
    public var credentials: [Credential]

    public init(credentials: [Credential]) {
        self.credentials = credentials
    }

    // swiftlint:disable force_cast
    public func presentMdocBLE(deviceEngagement: DeviceEngagement,
                               callback: BLESessionStateDelegate,
                               useL2CAP: Bool = true
                               // , trustedReaders: TrustedReaders
    ) async -> IsoMdlPresentation? {
        if let firstMdoc = self.credentials.first(where: {$0 is MDoc}) {
            return await IsoMdlPresentation(mdoc: firstMdoc as! MDoc,
                                     engagement: DeviceEngagement.QRCode,
                                     callback: callback,
                                      useL2CAP: useL2CAP)
        } else {
            return nil
        }
    }
    // swiftlint:enable force_cast
}
