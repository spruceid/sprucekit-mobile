import CoreBluetooth
import SpruceIDMobileSdkRs

let holderStateCharacteristicId = CBUUID(string: "00000001-A123-48CE-896B-4C76973373E6")
let holderClient2ServerCharacteristicId = CBUUID(string: "00000002-A123-48CE-896B-4C76973373E6")
let holderServer2ClientCharacteristicId = CBUUID(string: "00000003-A123-48CE-896B-4C76973373E6")
let holderL2CAPCharacteristicId = CBUUID(string: "0000000A-A123-48CE-896B-4C76973373E6")

let readerStateCharacteristicId = CBUUID(string: "00000005-A123-48CE-896B-4C76973373E6")
let readerClient2ServerCharacteristicId = CBUUID(string: "00000006-A123-48CE-896B-4C76973373E6")
let readerServer2ClientCharacteristicId = CBUUID(string: "00000007-A123-48CE-896B-4C76973373E6")
let readerIdentCharacteristicId = CBUUID(string: "00000008-A123-48CE-896B-4C76973373E6")
let readerL2CAPCharacteristicId = CBUUID(string: "0000000B-A123-48CE-896B-4C76973373E6")

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
