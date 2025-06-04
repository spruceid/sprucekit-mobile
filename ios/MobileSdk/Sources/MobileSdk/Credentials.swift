import Foundation
import SpruceIDMobileSdkRs

public class CredentialStore {
    public var credentials: [ParsedCredential]

    public init(credentials: [ParsedCredential]) {
        self.credentials = credentials
    }

    public func presentMdocBLE(deviceEngagement _: DeviceEngagementType,
                               callback: BLESessionStateDelegate,
                               useL2CAP: Bool = true
                               // , trustedReaders: TrustedReaders
    ) -> IsoMdlPresentation? {
        if let firstMdoc = credentials.first(where: { $0.asMsoMdoc() != nil }) {
            let mdoc = firstMdoc.asMsoMdoc()!
            return IsoMdlPresentation(mdoc: MDoc(Mdoc: mdoc), engagement: DeviceEngagementType.qr,
                                            callback: callback,
                                            useL2CAP: useL2CAP)
        } else {
            return nil
        }
    }
}
