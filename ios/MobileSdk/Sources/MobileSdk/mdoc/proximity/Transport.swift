import Foundation

protocol Transport {
    /// Request to send a message to the other party. Returns true if the message was accepted, and false otherwise.
    /// True does not guarantee that the message is sent, see TransportCallback for tracking the progress.
    func send(message: Data) -> Bool
}

protocol TransportCallback: NSObject {
    /// Transport is receving a message from the other party.
    func received(bytesSoFar: Int, outOfTotalBytes: Int?)

    /// Transport has received a message from the other party.
    func received(message: Data)

    /// Transport is sending a message, here is the progress.
    func sent(bytesSoFar: Int, outOfTotalBytes: Int)

    /// Transport has sent a message to the other party.
    func sent()

    /// An action must be performed to continue.
    func required(action: RequiredAction)
}

/// Options for transmitting the mdoc.
public enum TransmissionOption {
    case bleMdocCentralMode(L2CAPUsage)
    case bleMdocPeripheralMode(L2CAPUsage)
}

/// An action must be performed to continue
public enum RequiredAction: Equatable {
    /// The device's bluetooth must be turned on to continue.
    case turnOnBluetooth
    /// The user rejected the request to authorize this app to use bluetooth, but they must authorize the app to
    /// continue. You can use the following to open your apps settings page, where the user can authorize this:
    /// ```
    /// if let url = URL(string: UIApplication.openSettingsURLString) {
    ///     UIApplication.shared.open(url)
    /// }
    /// ```
    case authorizeBluetoothForApp
}
