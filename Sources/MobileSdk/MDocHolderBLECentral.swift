import Algorithms
import CoreBluetooth
import Foundation
import os
import SpruceIDMobileSdkRs

enum CharacteristicsError: Error {
    case missingMandatoryCharacteristic(name: String)
    case missingMandatoryProperty(name: String, characteristicName: String)
}

enum DataError: Error {
    case noData(characteristic: CBUUID)
    case invalidStateLength
    case unknownState(byte: UInt8)
    case unknownCharacteristic(uuid: CBUUID)
    case unknownDataTransferPrefix(byte: UInt8)
}

class MDocHolderBLECentral: NSObject {
    var centralManager: CBCentralManager!
    var serviceUuid: CBUUID
    var callback: MDocBLEDelegate
    var peripheral: CBPeripheral?
    var writeCharacteristic: CBCharacteristic?
    var readCharacteristic: CBCharacteristic?
    var stateCharacteristic: CBCharacteristic?
    var maximumCharacteristicSize: Int?
    var writingQueueTotalChunks = 0
    var writingQueueChunkIndex = 0
    var writingQueue: IndexingIterator<ChunksOfCountCollection<Data>>?

    var incomingMessageBuffer = Data()

    init(callback: MDocBLEDelegate, serviceUuid: CBUUID) {
        self.serviceUuid = serviceUuid
        self.callback = callback
        super.init()
        self.centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    func startScanning() {
        centralManager.scanForPeripherals(withServices: [serviceUuid])
    }

    func disconnectFromDevice () {
        let message: Data
        do {
            message = try terminateSession()
        } catch {
            print("\(error)")
            message = Data([0x02])
        }
        peripheral?.writeValue(_: message,
                               for: stateCharacteristic!,
                               type: CBCharacteristicWriteType.withoutResponse)
        disconnect()
    }

    private func disconnect() {
        if let peripheral = self.peripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
    }

    func writeOutgoingValue(data: Data) {
        let chunks = data.chunks(ofCount: maximumCharacteristicSize! - 1)
        writingQueueTotalChunks = chunks.count
        writingQueue = chunks.makeIterator()
        writingQueueChunkIndex = 0
        drainWritingQueue()
    }

    private func drainWritingQueue() {
        if writingQueue != nil {
            if var chunk = writingQueue?.next() {
                var firstByte: Data.Element
                writingQueueChunkIndex += 1
                if writingQueueChunkIndex == writingQueueTotalChunks {
                    firstByte = 0x00
                } else {
                    firstByte = 0x01
                }
                chunk.reverse()
                chunk.append(firstByte)
                chunk.reverse()
                self.callback.callback(message: .uploadProgress(writingQueueChunkIndex, writingQueueTotalChunks))
                peripheral?.writeValue(_: chunk,
                                       for: writeCharacteristic!,
                                       type: CBCharacteristicWriteType.withoutResponse)
            } else {
                self.callback.callback(message: .uploadProgress(writingQueueTotalChunks, writingQueueTotalChunks))
                writingQueue = nil
            }
        }
    }

    func processCharacteristics(peripheral: CBPeripheral, characteristics: [CBCharacteristic]) throws {
        if let characteristic = characteristics.first(where: {$0.uuid == readerStateCharacteristicId}) {
            if !characteristic.properties.contains(CBCharacteristicProperties.notify) {
                throw CharacteristicsError.missingMandatoryProperty(name: "notify", characteristicName: "State")
            }
            if !characteristic.properties.contains(CBCharacteristicProperties.writeWithoutResponse) {
                throw CharacteristicsError.missingMandatoryProperty(
                    name: "write without response",
                    characteristicName: "State"
                )
            }
            self.stateCharacteristic = characteristic
        } else {
            throw CharacteristicsError.missingMandatoryCharacteristic(name: "State")
        }

        if let characteristic = characteristics.first(where: {$0.uuid == readerClient2ServerCharacteristicId}) {
            if !characteristic.properties.contains(CBCharacteristicProperties.writeWithoutResponse) {
                throw CharacteristicsError.missingMandatoryProperty(
                    name: "write without response",
                    characteristicName: "Client2Server"
                )
            }
            self.writeCharacteristic = characteristic
        } else {
            throw CharacteristicsError.missingMandatoryCharacteristic(name: "Client2Server")
        }

        if let characteristic = characteristics.first(where: {$0.uuid == readerServer2ClientCharacteristicId}) {
            if !characteristic.properties.contains(CBCharacteristicProperties.notify) {
                throw CharacteristicsError.missingMandatoryProperty(name: "notify", characteristicName: "Server2Client")
            }
            self.readCharacteristic = characteristic
        } else {
            throw CharacteristicsError.missingMandatoryCharacteristic(name: "Server2Client")
        }

        if let characteristic = characteristics.first(where: {$0.uuid == readerIdentCharacteristicId}) {
            if !characteristic.properties.contains(CBCharacteristicProperties.read) {
                throw CharacteristicsError.missingMandatoryProperty(name: "read", characteristicName: "Ident")
            }
            peripheral.readValue(for: characteristic)
        } else {
            throw CharacteristicsError.missingMandatoryCharacteristic(name: "Ident")
        }

        if let characteristic = characteristics.first(where: {$0.uuid == readerL2CAPCharacteristicId}) {
            if !characteristic.properties.contains(CBCharacteristicProperties.read) {
                throw CharacteristicsError.missingMandatoryProperty(name: "read", characteristicName: "L2CAP")
            }
        }

//       iOS controls MTU negotiation. Since MTU is just a maximum, we can use a lower value than the negotiated value.
//       18013-5 expects an upper limit of 515 MTU, so we cap at this even if iOS negotiates a higher value.
//       
//       maximumWriteValueLength() returns the maximum characteristic size, which is 3 less than the MTU.
       let negotiatedMaximumCharacteristicSize = peripheral.maximumWriteValueLength(for: .withoutResponse)
       maximumCharacteristicSize = min(negotiatedMaximumCharacteristicSize - 3, 512)

    }

    func processData(peripheral: CBPeripheral, characteristic: CBCharacteristic) throws {
        if var data = characteristic.value {
            print("Processing data for \(characteristic.uuid)")
            switch characteristic.uuid {
            case readerStateCharacteristicId:
                if data.count != 1 {
                    throw DataError.invalidStateLength
                }
                switch data[0] {
                case 0x02:
                    self.callback.callback(message: .done)
                    self.disconnect()
                case let byte:
                    throw DataError.unknownState(byte: byte)
                }
            case readerServer2ClientCharacteristicId:
                let firstByte = data.popFirst()
                incomingMessageBuffer.append(data)
                switch firstByte {
                case .none:
                    throw DataError.noData(characteristic: characteristic.uuid)
                case 0x00: // end
                    print("End of message")
                    self.callback.callback(message: MDocBLECallback.message(incomingMessageBuffer))
                    self.incomingMessageBuffer = Data()
                    return
                case 0x01: // partial
                    print("Partial message")
                    // TODO check length against MTU
                    return
                case let .some(byte):
                    throw DataError.unknownDataTransferPrefix(byte: byte)
                }
            // Looks like this should just happen after discovering characteristics
            case readerIdentCharacteristicId:
                self.peripheral?.setNotifyValue(true, for: self.readCharacteristic!)
                self.peripheral?.setNotifyValue(true, for: self.stateCharacteristic!)
                self.peripheral?.writeValue(_: Data([0x01]),
                                            for: self.stateCharacteristic!,
                                            type: CBCharacteristicWriteType.withoutResponse)
                return
            case readerL2CAPCharacteristicId:
                return
            case let uuid:
                throw DataError.unknownCharacteristic(uuid: uuid)
            }
        } else {
            throw DataError.noData(characteristic: characteristic.uuid)
        }
    }
}

extension MDocHolderBLECentral: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            startScanning()
        } else {
            self.callback.callback(message: .error(.bluetooth(central)))
        }
    }
    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        print("Discovered peripheral")
        peripheral.delegate = self
        self.peripheral = peripheral
        centralManager?.connect(peripheral, options: nil)
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        print("Connected to peripheral")
        centralManager?.stopScan()
        peripheral.discoverServices([self.serviceUuid])
        self.callback.callback(message: .connected)
    }
}

extension MDocHolderBLECentral: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if (error) != nil {
            self.callback.callback(
                message: .error(.peripheral("Error discovering services: \(error!.localizedDescription)"))
            )
            return
        }
        if let services = peripheral.services {
            print("Discovered services")
            for service in services {
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if (error) != nil {
            self.callback.callback(
                message: .error(.peripheral("Error discovering characteristics: \(error!.localizedDescription)"))
            )
            return
        }
        if let characteristics = service.characteristics {
            print("Discovered characteristics")
            do {
                try self.processCharacteristics(peripheral: peripheral, characteristics: characteristics)
            } catch {
                self.callback.callback(message: .error(.peripheral("\(error)")))
                centralManager?.cancelPeripheralConnection(peripheral)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        do {
            print("Processing data")
            try self.processData(peripheral: peripheral, characteristic: characteristic)
        } catch {
            self.callback.callback(message: .error(.peripheral("\(error)")))
            centralManager?.cancelPeripheralConnection(peripheral)
        }
    }

    /// Notifies that the peripheral write buffer has space for more chunks.
    /// This is called after the buffer gets filled to capacity, and then has space again.
    ///
    /// Only available on iOS 11 and up.
    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        drainWritingQueue()
    }
}

extension MDocHolderBLECentral: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            print("Peripheral Is Powered On.")
        case .unsupported:
            print("Peripheral Is Unsupported.")
        case .unauthorized:
            print("Peripheral Is Unauthorized.")
        case .unknown:
            print("Peripheral Unknown")
        case .resetting:
            print("Peripheral Resetting")
        case .poweredOff:
            print("Peripheral Is Powered Off.")
        @unknown default:
            print("Error")
        }
    }
}
