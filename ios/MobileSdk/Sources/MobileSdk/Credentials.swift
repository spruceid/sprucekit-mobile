import Foundation
import SpruceIDMobileSdkRs

public class CredentialStore {
    public var credentials: [ParsedCredential]

    public init(credentials: [ParsedCredential]) {
        self.credentials = credentials
    }
}
