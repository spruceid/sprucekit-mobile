import Algorithms
import CoreBluetooth
import Foundation
import SpruceIDMobileSdkRs

class MDocReaderBLEPeripheral: NSObject {
    var peripheralManager: CBPeripheralManager!
    var serviceUuid: CBUUID
    var bleIdent: Data
    var incomingMessageBuffer = Data()
    var incomingMessageIndex = 0
    var callback: MDocReaderBLEDelegate
    var writeCharacteristic: CBMutableCharacteristic?
    var readCharacteristic: CBMutableCharacteristic?
    var stateCharacteristic: CBMutableCharacteristic?
    var identCharacteristic: CBMutableCharacteristic?
    var l2capCharacteristic: CBMutableCharacteristic?
    var requestData: Data
    var maximumCharacteristicSize: Int?
    var writingQueueTotalChunks: Int?
    var writingQueueChunkIndex: Int?
    var writingQueue: IndexingIterator<ChunksOfCountCollection<Data>>?

    init(callback: MDocReaderBLEDelegate, serviceUuid: CBUUID, request: Data, bleIdent: Data) {
        self.serviceUuid = serviceUuid
        self.callback = callback
        self.bleIdent = bleIdent
        self.requestData = request
        self.incomingMessageBuffer = Data()
        super.init()
        self.peripheralManager = CBPeripheralManager(delegate: self,
                                                     queue: nil,
                                                     options: [CBPeripheralManagerOptionShowPowerAlertKey: true])
    }

    func setupService() {
        let service = CBMutableService(type: self.serviceUuid, primary: true)
        self.stateCharacteristic = CBMutableCharacteristic(type: readerStateCharacteristicId,
                                                           properties: [.notify, .writeWithoutResponse, .write],
                                                           value: nil,
                                                           permissions: [.writeable])
        self.readCharacteristic = CBMutableCharacteristic(type: readerClient2ServerCharacteristicId,
                                                          properties: [.writeWithoutResponse, .write],
                                                          value: nil,
                                                          permissions: [.writeable])
        self.writeCharacteristic = CBMutableCharacteristic(type: readerServer2ClientCharacteristicId,
                                                           properties: [.notify],
                                                           value: nil,
                                                           permissions: [.readable, .writeable])
        self.identCharacteristic = CBMutableCharacteristic(type: readerIdentCharacteristicId,
                                                           properties: [.read],
                                                           value: bleIdent,
                                                           permissions: [.readable])
        //        self.l2capCharacteristic = CBMutableCharacteristic(type: readerL2CAPCharacteristicId,
        //                                                           properties: [.read],
        //                                                           value: nil,
        //                                                           permissions: [.readable])
        service.characteristics = (service.characteristics ?? []) + [
            stateCharacteristic! as CBCharacteristic,
            readCharacteristic! as CBCharacteristic,
            writeCharacteristic! as CBCharacteristic,
            identCharacteristic! as CBCharacteristic
            //            l2capCharacteristic! as CBCharacteristic
        ]
        peripheralManager.add(service)
    }

    func disconnect() {
        return
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
                writingQueueChunkIndex! += 1
                if writingQueueChunkIndex == writingQueueTotalChunks {
                    firstByte = 0x00
                } else {
                    firstByte = 0x01
                }
                chunk.reverse()
                chunk.append(firstByte)
                chunk.reverse()
                self.peripheralManager?.updateValue(chunk, for: self.writeCharacteristic!, onSubscribedCentrals: nil)
            } else {
                writingQueue = nil
            }
        }
    }

    func processData(central: CBCentral, characteristic: CBCharacteristic, value: Data?) throws {
        if var data = value {
            print("Processing data for \(characteristic.uuid)")
            switch characteristic.uuid {
            case readerClient2ServerCharacteristicId:
                let firstByte = data.popFirst()
                incomingMessageBuffer.append(data)
                switch firstByte {
                case .none:
                    throw DataError.noData(characteristic: characteristic.uuid)
                case 0x00: // end
                    print("End of message")
                    self.callback.callback(message: MDocReaderBLECallback.message(incomingMessageBuffer))
                    self.incomingMessageBuffer = Data()
                    self.incomingMessageIndex = 0
                    return
                case 0x01: // partial
                    print("Partial message")
                    self.incomingMessageIndex += 1
                    self.callback.callback(message: .downloadProgress(self.incomingMessageIndex))
                    // TODO check length against MTU
                    return
                case let .some(byte):
                    throw DataError.unknownDataTransferPrefix(byte: byte)
                }
            case readerStateCharacteristicId:
                if data.count != 1 {
                    throw DataError.invalidStateLength
                }
                switch data[0] {
                case 0x01:
                    print("Starting to send request")
                    writeOutgoingValue(data: self.requestData)
                case let byte:
                    throw DataError.unknownState(byte: byte)
                }
                return
//            case readerL2CAPCharacteristicId:
//                return
            case let uuid:
                throw DataError.unknownCharacteristic(uuid: uuid)
            }
        } else {
            throw DataError.noData(characteristic: characteristic.uuid)
        }
    }
}

extension MDocReaderBLEPeripheral: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            print("Advertising...")
            setupService()
            peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUuid]])
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

    // This is called when there is space in the queue again (so it is part of the loop for drainWritingQueue)
    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        self.drainWritingQueue()
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        print("Subscribed to \(characteristic.uuid)")
        self.callback.callback(message: .connected)
        self.peripheralManager?.stopAdvertising()
        switch characteristic.uuid {
        case readerStateCharacteristicId:
            // This will trigger wallet-sdk-swift to send 0x01 to start the exchange
            peripheralManager.updateValue(bleIdent, for: self.identCharacteristic!, onSubscribedCentrals: nil)
            // This will trigger wallet-sdk-kt to send 0x01 to start the exchange
            peripheralManager.updateValue(Data([0x01]), for: self.stateCharacteristic!, onSubscribedCentrals: nil)
        case _:
            return
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        print("Received read request for \(request.characteristic.uuid)")

        // Since there is no callback for MTU on iOS we will grab it here.
        maximumCharacteristicSize = min(request.central.maximumUpdateValueLength, 512)

        if request.characteristic.uuid == readerIdentCharacteristicId {
            peripheralManager.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == readerL2CAPCharacteristicId {
//            peripheralManager.publishL2CAPChannel(withEncryption: true)
//            peripheralManager.respond(to: request, withResult: .success)
        } else {
            self.callback.callback(message:
                .error(.server("Read on unexpected characteristic with UUID \(request.characteristic.uuid)"))
            )
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            // Since there is no callback for MTU on iOS we will grab it here.
            maximumCharacteristicSize = min(request.central.maximumUpdateValueLength, 512)

            do {
                print("Processing request")
                try processData(central: request.central, characteristic: request.characteristic, value: request.value)
                // This can be removed, or return an error, once wallet-sdk-kt is fixed and uses withoutResponse writes
                if request.characteristic.properties.contains(.write) {
                    peripheralManager.respond(to: request, withResult: .success)
                }
            } catch {
                self.callback.callback(message: .error(.server("\(error)")))
                self.peripheralManager?.updateValue(Data([0x02]),
                                                    for: self.stateCharacteristic!,
                                                    onSubscribedCentrals: nil)
            }
        }
    }
}
