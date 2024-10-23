import Foundation
import SpruceIDMobileSdkRs

public class CredentialStore {
    public var credentials: [ParsedCredential]

    public init(credentials: [ParsedCredential]) {
        self.credentials = credentials
    }

    public func presentMdocBLE(deviceEngagement _: DeviceEngagement,
                               callback: BLESessionStateDelegate,
                               useL2CAP: Bool = true
                               // , trustedReaders: TrustedReaders
    ) -> IsoMdlPresentation? {
        if let firstMdoc = credentials.first(where: { $0.asMsoMdoc() != nil }) {
            let mdoc = firstMdoc.asMsoMdoc()!
            return IsoMdlPresentation(mdoc: MDoc(Mdoc: mdoc),
                                            engagement: DeviceEngagement.QRCode,
                                            callback: callback,
                                            useL2CAP: useL2CAP)
        } else {
            return nil
        }
    }
}
