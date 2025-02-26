import CoreBluetooth
import SpruceIDMobileSdkRs

/// (unsafe) Constant CBUUIDs are marked as sendable because the potential mutable state is what is preventing them to
/// be automatically Sendable, but they are constant.

nonisolated(unsafe) let holderStateCharacteristicId = CBUUID(string: "00000001-A123-48CE-896B-4C76973373E6")
nonisolated(unsafe) let holderClient2ServerCharacteristicId = CBUUID(string: "00000002-A123-48CE-896B-4C76973373E6")
nonisolated(unsafe) let holderServer2ClientCharacteristicId = CBUUID(string: "00000003-A123-48CE-896B-4C76973373E6")
nonisolated(unsafe) let holderL2CAPCharacteristicId = CBUUID(string: "0000000A-A123-48CE-896B-4C76973373E6")

nonisolated(unsafe) let readerStateCharacteristicId = CBUUID(string: "00000005-A123-48CE-896B-4C76973373E6")
nonisolated(unsafe) let readerClient2ServerCharacteristicId = CBUUID(string: "00000006-A123-48CE-896B-4C76973373E6")
nonisolated(unsafe) let readerServer2ClientCharacteristicId = CBUUID(string: "00000007-A123-48CE-896B-4C76973373E6")
nonisolated(unsafe) let readerIdentCharacteristicId = CBUUID(string: "00000008-A123-48CE-896B-4C76973373E6")
nonisolated(unsafe) let readerL2CAPCharacteristicId = CBUUID(string: "0000000B-A123-48CE-896B-4C76973373E6")

enum MdocHolderBleError {
    /// When discovery or communication with the peripheral fails
    case peripheral(String)
    /// When Bluetooth is unusable (e.g. unauthorized).
    case bluetooth(CBCentralManager)
}

enum MdocReaderBleError {
    /// When communication with the server fails
    case server(String)
    /// When Bluetooth is unusable (e.g. unauthorized).
    case bluetooth(CBCentralManager)
}

enum MDocBLECallback {
    case done
    case connected
    case message(Data)
    case error(MdocHolderBleError)
    /// Chunks sent so far and total number of chunks to be sent
    case uploadProgress(Int, Int)
}

protocol MDocBLEDelegate: AnyObject {
    func callback(message: MDocBLECallback)
}

enum MDocReaderBLECallback {
    case done([String: [String: MDocItem]])
    case connected
    case error(MdocReaderBleError)
    case message(Data)
    /// Chunks received so far
    case downloadProgress(Int)
}

protocol MDocReaderBLEDelegate: AnyObject {
    func callback(message: MDocReaderBLECallback)
}

/// Return a string describing a BLE characteristic property.
func MDocCharacteristicPropertyName(_ prop: CBCharacteristicProperties) -> String {
    return switch prop {
    case .broadcast: "broadcast"
    case .read: "read"
    case .writeWithoutResponse: "write without response"
    case .write: "write"
    case .notify: "notify"
    case .indicate: "indicate"
    case .authenticatedSignedWrites: "authenticated signed writes"
    case .extendedProperties: "extended properties"
    case .notifyEncryptionRequired: "notify encryption required"
    case .indicateEncryptionRequired: "indicate encryption required"
    default: "unknown property"
    }
}

/// Return a string describing a BLE characteristic.
func MDocCharacteristicName(_ chr: CBCharacteristic) -> String {
    return MDocCharacteristicNameFromUUID(chr.uuid)
}

/// Return a string describing a BLE characteristic given its UUID.
func MDocCharacteristicNameFromUUID(_ chr: CBUUID) -> String {
    return switch chr {
    case holderStateCharacteristicId: "Holder:State"
    case holderClient2ServerCharacteristicId: "Holder:Client2Server"
    case holderServer2ClientCharacteristicId: "Holder:Server2Client"
    case holderL2CAPCharacteristicId: "Holder:L2CAP"
    case readerStateCharacteristicId: "Reader:State"
    case readerClient2ServerCharacteristicId: "Reader:Client2Server"
    case readerServer2ClientCharacteristicId: "Reader:Server2Client"
    case readerIdentCharacteristicId: "Reader:Ident"
    case readerL2CAPCharacteristicId: "Reader:L2CAP"
    default: "Unknown:\(chr)"
    }
}

/// Print a description of a BLE characteristic.
func MDocDesribeCharacteristic(_ chr: CBCharacteristic) {
    print("        \(MDocCharacteristicName(chr)) ( ", terminator: "")

    if chr.properties.contains(.broadcast) { print("broadcast", terminator: " ") }
    if chr.properties.contains(.read) { print("read", terminator: " ") }
    if chr.properties.contains(.writeWithoutResponse) { print("writeWithoutResponse", terminator: " ") }
    if chr.properties.contains(.write) { print("write", terminator: " ") }
    if chr.properties.contains(.notify) { print("notify", terminator: " ") }
    if chr.properties.contains(.indicate) { print("indicate", terminator: " ") }
    if chr.properties.contains(.authenticatedSignedWrites) { print("authenticatedSignedWrites", terminator: " ") }
    if chr.properties.contains(.extendedProperties) { print("extendedProperties", terminator: " ") }
    if chr.properties.contains(.notifyEncryptionRequired) { print("notifyEncryptionRequired", terminator: " ") }
    if chr.properties.contains(.indicateEncryptionRequired) { print("indicateEncryptionRequired", terminator: " ") }
    print(")")

    if let descriptors = chr.descriptors {
        for desc in descriptors {
            print("          : \(desc.uuid)")
        }
    } else {
        print("          <no descriptors>")
    }
}
