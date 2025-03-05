// Derived from MIT-licensed work by Paul Wilkinson: https://github.com/paulw11/L2Cap

import CoreBluetooth
import Foundation

protocol MDocReaderBLEPeriConnDelegate: AnyObject {
    func streamOpen()
    func sentData(_ bytes: Int)
    func receivedData(_ data: Data)
}

class MDocReaderBLEPeripheralConnection: BLEInternalL2CAPConnection {
    private let controlDelegate: MDocReaderBLEPeriConnDelegate

    /// Initialize a reader peripheral connection.
    init(delegate: MDocReaderBLEPeriConnDelegate, channel: CBL2CAPChannel) {
        controlDelegate = delegate
        super.init()
        self.channel = channel
        channel.inputStream.delegate = self
        channel.outputStream.delegate = self
        channel.inputStream.schedule(in: RunLoop.main, forMode: .default)
        channel.outputStream.schedule(in: RunLoop.main, forMode: .default)
        channel.inputStream.open()
        channel.outputStream.open()
    }

    /// Called by super when the stream opens.
    override func streamIsOpen() {
        controlDelegate.streamOpen()
    }

    /// Called by super when the stream ends.
    override func streamEnded() {
        close()
    }

    /// Called by super when the stream has bytes available for reading.
    override func streamBytesAvailable() {}

    /// Called by super when the stream has buffer space available for sending.
    override func streamSpaceAvailable() {}

    /// Called by super when the stream has an error.
    override func streamError() {
        close()
    }

    /// Called by super when the stream has an unknown event; these can probably be ignored.
    override func streamUnknownEvent() {}

    /// Called by super when data is sent.
    override func streamSentData(bytes: Int, total: Int, fraction: Double) {
        print("Stream sent \(bytes) of \(total) bytes, \(fraction * 100)% complete.")
        controlDelegate.sentData(bytes)
    }

    /// Called by super when data is received.
    override func streamReceivedData(_ data: Data) {
        controlDelegate.receivedData(data)
    }
}
