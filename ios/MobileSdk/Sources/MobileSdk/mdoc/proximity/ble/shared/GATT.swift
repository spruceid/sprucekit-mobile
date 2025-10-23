import CoreBluetooth
import Foundation

private let more: UInt8 = 0x01
private let last: UInt8 = 0x00

/// The value written to the state characteristic by the central when it is ready to begin transmission.
let connectionStateStart = Data([0x01])
/// The value written to the state characteristic by either party when it ends the connection.
let connectionStateEnd = Data([0x02])

/// An inbox for handling incoming serial messages conforming to the 18013-5 specification.
class GATTInbox {
    private let delegate: TransportCallback

    private var message = Data()

    init(delegate: TransportCallback) {
        self.delegate = delegate
    }

    /// Accept and process a chunk of data.
    func accept(chunk: Data) {
        let head = chunk[0]
        message += chunk[1...]

        switch head {
        case more:
            delegate.received(bytesSoFar: message.count, outOfTotalBytes: nil)
        case last:
            let complete = message
            message = Data()
            print("received complete message from the client")
            delegate.received(message: complete)
        default:
            message = Data()
            print("received an invalid message: unknown header byte \(head), resetting inbox")
        }
    }
}

/// An outbox for handling outgoing serial messages conforming to the 18013-5 specification.
class GATTOutbox {
    private let mtu: Int
    private let delegate: TransportCallback

    private var message: Data?
    private var written: Int = 0
    private var total: Int = 0

    init(mtu: Int, delegate: TransportCallback) {
        self.mtu = mtu
        self.delegate = delegate
    }

    func send(message: Data) -> Status {
        if written == total {
            self.message = message
            total = message.count
            written = 0
            return .accepted
        }
        return .busy(message)
    }

    func nextChunk() -> Chunk? {
        guard let message = message else {
            return nil
        }
        if written == total {
            return nil
        }
        // Slice the next chunk, and add the appropriate prefix.
        var next = Data([more])
        let fromByte = written
        var toByte = written + mtu - 1
        if toByte > total {
            next = Data([last])
            toByte = total
        }
        next.append(message[fromByte ..< toByte])

        return Chunk(chunk: next, from: self)
    }

    private func commit(_ written: Int) {
        self.written += written
        delegate.sent(bytesSoFar: self.written, outOfTotalBytes: total)
    }

    /// One chunk of the message.
    class Chunk {
        let data: Data
        private let outbox: GATTOutbox

        fileprivate init(chunk: Data, from: GATTOutbox) {
            data = chunk
            outbox = from
        }

        consuming func commit() {
            outbox.commit(data.count - 1)
            if outbox.written == outbox.total {
                outbox.delegate.sent()
            }
        }
    }

    enum Status {
        /// Accepted the message to send.
        case accepted
        /// Outbox is busy.
        case busy(Data)
    }
}

class CharacteristicUuids {
    /// The GATT characteristic UUIDs used by the Mdoc when it is the peripheral.
    class Mdoc {
        static let state = CBUUID(string: "00000001-A123-48CE-896B-4C76973373E6")
        static let client2Server = CBUUID(string: "00000002-A123-48CE-896B-4C76973373E6")
        static let server2Client = CBUUID(string: "00000003-A123-48CE-896B-4C76973373E6")
        static let l2cap = CBUUID(string: "0000000A-A123-48CE-896B-4C76973373E6")
    }

    /// The GATT characteristic UUIDs used by the Reader when it is the peripheral.
    class Reader {
        static let state = CBUUID(string: "00000005-A123-48CE-896B-4C76973373E6")
        static let client2Server = CBUUID(string: "00000006-A123-48CE-896B-4C76973373E6")
        static let server2Client = CBUUID(string: "00000007-A123-48CE-896B-4C76973373E6")
        static let ident = CBUUID(string: "00000008-A123-48CE-896B-4C76973373E6")
        static let l2cap = CBUUID(string: "0000000B-A123-48CE-896B-4C76973373E6")
    }
}
