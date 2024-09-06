import CoreBluetooth
import CryptoKit
import Foundation
import SpruceIDMobileSdkRs

public typealias MDocNamespace = String
public typealias IssuerSignedItemBytes = Data
public typealias ItemsRequest = SpruceIDMobileSdkRs.ItemsRequest

public class MDoc: Credential {
    var inner: SpruceIDMobileSdkRs.MDoc
    var keyAlias: String

    /// issuerAuth is the signed MSO (i.e. CoseSign1 with MSO as payload)
    /// namespaces is the full set of namespaces with data items and their value
    /// IssuerSignedItemBytes will be bytes, but its composition is defined here
    /// https://github.com/spruceid/isomdl/blob/f7b05dfa/src/definitions/issuer_signed.rs#L18
    public init?(fromMDoc issuerAuth: Data, namespaces: [MDocNamespace: [IssuerSignedItemBytes]], keyAlias: String) {
        self.keyAlias = keyAlias
        do {
            try self.inner = SpruceIDMobileSdkRs.MDoc.fromCbor(value: issuerAuth)
        } catch {
            print("\(error)")
            return nil
        }
        super.init(id: inner.id())
    }
}

public enum DeviceEngagement {
    case QRCode
}

/// To be implemented by the consumer to update the UI
public protocol BLESessionStateDelegate: AnyObject {
    func update(state: BLESessionState)
}

public class BLESessionManager {
    var callback: BLESessionStateDelegate
    var uuid: UUID
    var state: SessionManagerEngaged
    var sessionManager: SessionManager?
    var mdoc: MDoc
    var bleManager: MDocHolderBLECentral!
    var useL2CAP: Bool

    init?(mdoc: MDoc, engagement: DeviceEngagement, callback: BLESessionStateDelegate, useL2CAP: Bool) {
        self.callback = callback
        self.uuid = UUID()
        self.mdoc = mdoc
        self.useL2CAP = useL2CAP
        do {
            let sessionData = try SpruceIDMobileSdkRs.initialiseSession(document: mdoc.inner,
                                                                        uuid: self.uuid.uuidString)
            self.state = sessionData.state
            bleManager = MDocHolderBLECentral(callback: self,
                                              serviceUuid: CBUUID(nsuuid: self.uuid),
                                              useL2CAP: useL2CAP)
            self.callback.update(state: .engagingQRCode(sessionData.qrCodeUri.data(using: .ascii)!))
        } catch {
            print("\(error)")
            return nil
        }
    }

    // Cancel the request mid-transaction and gracefully clean up the BLE stack.
    public func cancel() {
        bleManager.disconnectFromDevice()
    }

    public func submitNamespaces(items: [String: [String: [String]]]) {
        do {
            let payload = try SpruceIDMobileSdkRs.submitResponse(sessionManager: sessionManager!,
                                                                 permittedItems: items)
            let query = [kSecClass: kSecClassKey,
          kSecAttrApplicationLabel: self.mdoc.keyAlias,
                     kSecReturnRef: true] as [String: Any]

            // Find and cast the result as a SecKey instance.
            var item: CFTypeRef?
            var secKey: SecKey
            switch SecItemCopyMatching(query as CFDictionary, &item) {
            case errSecSuccess:
                // swiftlint:disable force_cast
                secKey = item as! SecKey
                // swiftlint:enable force_cast
            case errSecItemNotFound:
                self.callback.update(state: .error(.generic("Key not found")))
                self.cancel()
                return
            case let status:
                self.callback.update(state: .error(.generic("Keychain read failed: \(status)")))
                self.cancel()
                return
            }
            var error: Unmanaged<CFError>?
            guard let derSignature = SecKeyCreateSignature(secKey,
                                                           .ecdsaSignatureMessageX962SHA256,
                                                           payload as CFData,
                                                           &error) as Data? else {
                self.callback.update(state: .error(.generic("Failed to sign message: \(error.debugDescription)")))
                self.cancel()
                return
            }
            let response = try SpruceIDMobileSdkRs.submitSignature(sessionManager: sessionManager!,
                                                                     derSignature: derSignature)
            self.bleManager.writeOutgoingValue(data: response)
        } catch {
            self.callback.update(state: .error(.generic("\(error)")))
            self.cancel()
        }
    }
}

extension BLESessionManager: MDocBLEDelegate {
    func callback(message: MDocBLECallback) {
        switch message {
        case .done:
            self.callback.update(state: .success)
        case .connected:
            self.callback.update(state: .connected)
        case .uploadProgress(let value, let total):
            self.callback.update(state: .uploadProgress(value, total))
        case .message(let data):
            do {
                let requestData = try SpruceIDMobileSdkRs.handleRequest(state: self.state, request: data)
                self.sessionManager = requestData.sessionManager
                self.callback.update(state: .selectNamespaces(requestData.itemsRequests))
            } catch {
                self.callback.update(state: .error(.generic("\(error)")))
                self.cancel()
            }
        case .error(let error):
            self.callback.update(state: .error(BleSessionError(holderBleError: error)))
            self.cancel()
        }
    }
}

public enum BleSessionError {
    /// When discovery or communication with the peripheral fails
    case peripheral(String)
    /// When Bluetooth is unusable (e.g. unauthorized).
    case bluetooth(CBCentralManager)
    /// Generic unrecoverable error
    case generic(String)

    init(holderBleError: MdocHolderBleError) {
        switch holderBleError {
        case .peripheral(let string):
            self = .peripheral(string)
        case .bluetooth(let string):
            self = .bluetooth(string)
        }
    }
}

public enum BLESessionState {
    /// App should display the error message
    case error(BleSessionError)
    /// App should display the QR code
    case engagingQRCode(Data)
    /// App should indicate to the user that BLE connection has been made
    case connected
    /// App should display an interactive page for the user to chose which values to reveal
    case selectNamespaces([ItemsRequest])
    /// App should display the fact that a certain percentage of data has been sent
    /// - Parameters:
    ///   - 0: The number of chunks sent to far
    ///   - 1: The total number of chunks to be sent
    case uploadProgress(Int, Int)
    /// App should display a success message and offer to close the page
    case success
}
