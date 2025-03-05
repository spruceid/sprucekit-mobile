import CoreBluetooth
import Foundation
import SpruceIDMobileSdkRs

public enum DeviceEngagement {
    case QRCode
}

/// To be implemented by the consumer to update the UI
public protocol BLESessionStateDelegate: AnyObject {
    func update(state: BLESessionState)
}

public class IsoMdlPresentation {
    var callback: BLESessionStateDelegate
    var uuid: UUID
    var session: MdlPresentationSession
    var mdoc: MDoc
    var bleManager: MDocHolderBLECentral!
    var useL2CAP: Bool

    public init?(
        mdoc: MDoc, engagement: DeviceEngagement,
        callback: BLESessionStateDelegate, useL2CAP: Bool
    ) {
        self.callback = callback
        self.uuid = UUID()
        self.mdoc = mdoc
        self.useL2CAP = useL2CAP
        do {
            self.session =
                try SpruceIDMobileSdkRs.initializeMdlPresentationFromBytes(
                    mdoc: mdoc.inner, uuid: self.uuid.uuidString)
            bleManager = MDocHolderBLECentral(
                callback: self,
                serviceUuid: CBUUID(nsuuid: self.uuid),
                useL2CAP: useL2CAP)
            self.callback.update(
                state: .engagingQRCode(
                    session.getQrCodeUri().data(using: .ascii)!))
        } catch {
            print("\(error)")
            return nil
        }
    }

    // Cancel the request mid-transaction and gracefully clean up the BLE stack.
    public func cancel() {
        bleManager.disconnectFromDevice(session: self.session)
    }

    public func submitNamespaces(items: [String: [String: [String]]], signingKey: SecKey) {
        do {
            let payload = try session.generateResponse(permittedItems: items)

            var error: Unmanaged<CFError>?
            guard
                let derSignature = SecKeyCreateSignature(
                    signingKey,
                    .ecdsaSignatureMessageX962SHA256,
                    payload as CFData,
                    &error) as Data?
            else {
                self.callback.update(
                    state: .error(
                        .generic(
                            "Failed to sign message: \(error.debugDescription)")
                    ))
                self.cancel()
                return
            }
            guard let signature =
                    CryptoCurveUtils.secp256r1().ensureRawFixedWidthSignatureEncoding(bytes: derSignature) else {
                self.callback.update(
                    state: .error(
                        .generic(
                            "Failed to sign message: unrecognized signature encoding")
                    ))
                self.cancel()
                return
            }
            let response = try session.submitResponse(
                signature: signature)
            self.bleManager.writeOutgoingValue(data: response)
        } catch {
            self.callback.update(state: .error(.generic("\(error)")))
            self.cancel()
        }
    }
}

extension IsoMdlPresentation: MDocBLEDelegate {
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
                let itemsRequests = try session.handleRequest(request: data)
                self.callback.update(state: .selectNamespaces(itemsRequests))
            } catch {
                self.callback.update(state: .error(.generic("\(error)")))
                self.cancel()
            }
        case .error(let error):
            self.callback.update(
                state: .error(BleSessionError(holderBleError: error)))
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
