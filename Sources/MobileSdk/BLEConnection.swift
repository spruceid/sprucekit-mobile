// Derived from MIT-licensed work by Paul Wilkinson: https://github.com/paulw11/L2Cap

import CoreBluetooth
import Foundation

/// The base BLE connection, only intended for subclassing.
class BLEInternalL2CAPConnection: NSObject, StreamDelegate {
    var channel: CBL2CAPChannel?

    private var outputData = Data()
    private var outputDelivered = false
    private var incomingData = Data()
    private var incomingTime = Date(timeIntervalSinceNow: 0)
    private var incomingDelivered = false
    private var openCount = 0
    private var totalBytesWritten = 0

    /// Handle stream events.  Many of these we hand to local methods which the child classes are expected to
    /// override.
    func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case Stream.Event.openCompleted:
            // TODO: This is a bit of a hack, but it'll do for now.  There are two streams, one input, one
            // output, and we get notified about both.  We really only want to start doing things when
            // both are available.
            openCount += 1

            if openCount == 2 {
                streamIsOpen()
            }

        case Stream.Event.endEncountered:
            openCount -= 1
            streamEnded()

        case Stream.Event.hasBytesAvailable:
            streamBytesAvailable()
            if let stream = aStream as? InputStream {
                readBytes(from: stream)
            }

        case Stream.Event.hasSpaceAvailable:
            streamSpaceAvailable()
            send()

        case Stream.Event.errorOccurred:
            streamError()

        default:
            streamUnknownEvent()
        }
    }

    /// Public send() interface.
    public func send(data: Data) {
        if !outputDelivered {
            outputDelivered = true
            outputData = data
            totalBytesWritten = 0
            send()
        }
    }

    /// Internal send() interface.
    private func send() {
        guard let ostream = channel?.outputStream, !outputData.isEmpty, ostream.hasSpaceAvailable else {
            return
        }
        let bytesWritten = ostream.write(outputData)

        totalBytesWritten += bytesWritten

        // The isEmpty guard above should prevent div0 errors here.
        let fracDone = Double(totalBytesWritten) / Double(outputData.count)

        streamSentData(bytes: bytesWritten, total: totalBytesWritten, fraction: fracDone)

        if bytesWritten < outputData.count {
            outputData = outputData.advanced(by: bytesWritten)
        } else {
            outputData.removeAll()
        }
    }

    /// Close the stream.
    public func close() {
        if let chn = channel {
            chn.outputStream.close()
            chn.inputStream.close()
            chn.inputStream.remove(from: .main, forMode: .default)
            chn.outputStream.remove(from: .main, forMode: .default)
            chn.inputStream.delegate = nil
            chn.outputStream.delegate = nil
            openCount = 0
        }

        channel = nil
    }

    /// Read from the stream.
    private func readBytes(from stream: InputStream) {
        let bufLength = 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufLength)
        defer {
            buffer.deallocate()
        }
        let bytesRead = stream.read(buffer, maxLength: bufLength)
        incomingData.append(buffer, count: bytesRead)

        //    This is an awful hack to work around a hairy problem.  L2CAP is a stream protocol; there's
        // no framing on data, so there's no way to signal that the data exchange is complete.  In principle
        // we could build a framing protocol on top, or we could use the State characteristics to signal out
        // of band, but neither of those are specified by the spec, so we'd be out of compliance.  The State
        // signalling is what the non-L2CAP flow uses, but the spec explicitly says it's not used with L2CAP.
        //
        //    Another thing we could do would be close the connection, but there are two problems with that;
        // the first is we'd be out of spec compliance again, and the second is that we actually have two
        // messages going, one in each direction, serially.  If we closed to indicate the length of the first,
        // we'd have no connection for the second.
        //
        //    So, we have data coming in, and we don't know how much.  The stream lets us know when more data
        // has arrived, the data comes in chunks.  What we do, then, is timestamp when we receive some data,
        // and then half a second later see if we got any more.  Hopefully the half second delay is small
        // enough not to annoy the user and large enough to account for noisy radio environments, but the
        // numbers here are a heuristic, and may need to be tuned.  If we have no recent data, we assume
        // everything is ok, and declare the transmission complete.

        incomingTime = Date(timeIntervalSinceNow: 0)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { // Half second delay.
            if self.incomingDelivered {
                return
            }

            let timeSinceLastData = -self.incomingTime.timeIntervalSinceNow // Make it positive.
            let complete = timeSinceLastData > 0.25

            if complete {
                self.streamReceivedData(self.incomingData)
                self.incomingDelivered = true
            }
        }

        if stream.hasBytesAvailable {
            readBytes(from: stream)
        }
    }

    /// Methods to be overridden by child classes.
    func streamIsOpen() { print("The stream is open.") }
    func streamEnded() { print("The stream has ended.") }
    func streamBytesAvailable() { print("The stream has bytes available.") }
    func streamSpaceAvailable() { print("The stream has space available.") }
    func streamError() { print("Stream error.") }
    func streamUnknownEvent() { print("Stream unknown event.") }
    func streamSentData(bytes _: Int, total _: Int, fraction _: Double) { print("Stream sent data.") }
    func streamReceivedData(_: Data) { print("Stream received data.") }
}

/// A UInt16 from Data extension.
extension UInt16 {
    var data: Data {
        var int = self
        return Data(bytes: &int, count: MemoryLayout<UInt16>.size)
    }
}

/// A Data from UInt16 extension.
extension Data {
    var uint16: UInt16 {
        let i16array = withUnsafeBytes { $0.load(as: UInt16.self) }
        return i16array
    }
}

/// A write() on OutputStream extension.
extension OutputStream {
    func write(_ data: Data) -> Int {
        return data.withUnsafeBytes { (rawBufferPointer: UnsafeRawBufferPointer) -> Int in
            let bufferPointer = rawBufferPointer.bindMemory(to: UInt8.self)
            return self.write(bufferPointer.baseAddress!, maxLength: data.count)
        }
    }
}
