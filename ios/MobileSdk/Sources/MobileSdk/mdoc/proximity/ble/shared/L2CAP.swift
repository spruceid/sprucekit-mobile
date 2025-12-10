import CoreBluetooth
import Foundation

class L2CAPManager: BLEInternalL2CAPConnection {
    private let delegate: TransportCallback
    private var receivedBytes = 0

    init(channel: CBL2CAPChannel, delegate: TransportCallback) {
        self.delegate = delegate
        super.init()
        self.channel = channel
        channel.inputStream.delegate = self
        channel.outputStream.delegate = self
        channel.inputStream.schedule(in: RunLoop.main, forMode: .default)
        channel.outputStream.schedule(in: RunLoop.main, forMode: .default)
        channel.inputStream.open()
        channel.outputStream.open()
    }

    override func streamSentData(bytes sent: Int, total: Int, fraction _: Double) {
        // Always send progress so progress bar can reach 100%.
        delegate.sent(bytesSoFar: sent, outOfTotalBytes: total)

        if sent == total {
            delegate.sent()
        }
    }

    override func streamReceivingData(bytes: Int) {
        receivedBytes += bytes
        delegate.received(bytesSoFar: receivedBytes, outOfTotalBytes: nil)
    }

    override func streamReceivedData(_ message: Data) {
        receivedBytes = 0
        delegate.received(message: message)
    }
}

public enum L2CAPUsage {
    /// Never use L2CAP.
    case disableL2CAP
    /// Use L2CAP if available.
    case enableL2CAP
    /// Only use L2CAP - not recommended for a production application.
    case forceL2CAP
}
