// Derived from MIT-licensed work by Paul Wilkinson: https://github.com/paulw11/L2Cap

import CoreBluetooth
import Foundation

public protocol MDocHolderBLECentralConnectionDelegate: AnyObject {
    func request(_ data: Data)
    func sendUpdate(bytes: Int, total: Int, fraction: Double)
    func sendComplete()
    func connectionEnd()
}

class MDocHolderBLECentralConnection: BLEInternalL2CAPConnection, @unchecked Sendable {
    private let controlDelegate: MDocHolderBLECentralConnectionDelegate

    /// Initialize a reader peripheral connection.
    init(delegate: MDocHolderBLECentralConnectionDelegate, channel: CBL2CAPChannel) {
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

    /// Called by super when the stream is open.
    override func streamIsOpen() {}

    /// Called by super when the stream ends.
    override func streamEnded() {
        // Stream ended, this is expected when the transfer is complete
        print("L2CAP stream ended gracefully")
        close()
        controlDelegate.connectionEnd()
    }

    /// Called by super when the stream has readable data.
    override func streamBytesAvailable() {}

    /// Called by super when the stream has space in the outbound buffer.
    override func streamSpaceAvailable() {}

    /// Called by super if the stream encounters an error.
    override func streamError() {
        // Only log actual errors, not expected closures
        print("L2CAP stream error - likely due to intentional closure")
        close()
        controlDelegate.connectionEnd()
    }

    /// Called by super if an unknown stream event occurs.
    override func streamUnknownEvent() {}

    /// Called by super when data is sent.
    override func streamSentData(bytes: Int, total: Int, fraction: Double) {
        print("Stream sent \(bytes) of \(total) bytes, \(fraction * 100)% complete.")

        controlDelegate.sendUpdate(bytes: bytes, total: total, fraction: fraction)

        if fraction > 0.999 {
            controlDelegate.sendComplete()
        }
    }

    /// Called by super when data is received.
    override func streamReceivedData(_ data: Data) {
        controlDelegate.request(data)
    }
}
