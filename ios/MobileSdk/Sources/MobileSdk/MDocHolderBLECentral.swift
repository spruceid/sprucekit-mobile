import Algorithms
import CoreBluetooth
import Foundation
import os
import SpruceIDMobileSdkRs

/// Characteristic errors.
enum CharacteristicsError: Error {
    case missingMandatoryCharacteristic(name: String)
    case missingMandatoryProperty(name: String, characteristicName: String)
}

/// Data errors.
/// (unsafe) CBUUIDs are marked as sendable but they are not meant to be modified
enum DataError: Error, @unchecked Sendable {
    case noData(characteristic: CBUUID)
    case invalidStateLength
    case unknownState(byte: UInt8)
    case unknownCharacteristic(uuid: CBUUID)
    case unknownDataTransferPrefix(byte: UInt8)
}

/// The MDoc holder as a BLE central.
class MDocHolderBLECentral: NSObject {
    enum MachineState {
        case initial, hardwareOn, fatalError, complete, halted
        case awaitPeripheralDiscovery, peripheralDiscovered, checkPeripheral
        case awaitRequest, requestReceived, sendingResponse
        case l2capAwaitRequest, l2capRequestReceived, l2capSendingResponse
    }

    var centralManager: CBCentralManager!
    var serviceUuid: CBUUID
    var callback: MDocBLEDelegate
    var peripheral: CBPeripheral?

    var writeCharacteristic: CBCharacteristic?
    var readCharacteristic: CBCharacteristic?
    var stateCharacteristic: CBCharacteristic?
    var l2capCharacteristic: CBCharacteristic?

    var maximumCharacteristicSize: Int?
    var writingQueueTotalChunks = 0
    var writingQueueChunkIndex = 0
    var writingQueue: IndexingIterator<ChunksOfCountCollection<Data>>?

    var incomingMessageBuffer = Data()
    var outgoingMessageBuffer = Data()

    private var channelPSM: UInt16?
    private var activeStream: MDocHolderBLECentralConnection?

    /// If this is `false`, we decline to connect to L2CAP even if it is offered.
    var useL2CAP: Bool

    var machineState = MachineState.initial
    var machinePendingState = MachineState.initial {
        didSet {
            updateState()
        }
    }

    init(callback: MDocBLEDelegate, serviceUuid: CBUUID, useL2CAP: Bool) {
        self.serviceUuid = serviceUuid
        self.callback = callback
        self.useL2CAP = useL2CAP
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    /// Update the state machine.
    private func updateState() {
        var update = true

        while update {
            if machineState != machinePendingState {
                print("「\(machineState) → \(machinePendingState)」")
            } else {
                print("「\(machineState)」")
            }

            update = false

            switch machineState {
            /// Core.
            case .initial: // Object just initialized, hardware not ready.
                if machinePendingState == .hardwareOn {
                    machineState = machinePendingState
                    update = true
                }

            case .hardwareOn: // Hardware is ready.
                centralManager.scanForPeripherals(withServices: [serviceUuid])
                machineState = machinePendingState
                machinePendingState = .awaitPeripheralDiscovery

            case .awaitPeripheralDiscovery:
                if machinePendingState == .peripheralDiscovered {
                    machineState = machinePendingState
                }

            case .peripheralDiscovered:
                if machinePendingState == .checkPeripheral {
                    machineState = machinePendingState

                    centralManager?.stopScan()
                    callback.callback(message: .connected)
                }

            case .checkPeripheral:
                if machinePendingState == .awaitRequest {
                    if let peri = peripheral {
                        if useL2CAP, let l2capC = l2capCharacteristic {
                            peri.setNotifyValue(true, for: l2capC)
                            peri.readValue(for: l2capC)
                            machineState = .l2capAwaitRequest
                        } else if let readC = readCharacteristic,
                                  let stateC = stateCharacteristic {
                            peri.setNotifyValue(true, for: readC)
                            peri.setNotifyValue(true, for: stateC)
                            peri.writeValue(_: Data([0x01]), for: stateC, type: .withoutResponse)
                            machineState = machinePendingState
                        }
                    }
                }

            /// Original flow.
            case .awaitRequest:
                if machinePendingState == .requestReceived {
                    machineState = machinePendingState
                    callback.callback(message: MDocBLECallback.message(incomingMessageBuffer))
                    incomingMessageBuffer = Data()
                }

            /// The request has been received, we're waiting for the user to respond to the selective diclosure
            /// dialog.
            case .requestReceived:
                if machinePendingState == .sendingResponse {
                    machineState = machinePendingState
                    let chunks = outgoingMessageBuffer.chunks(ofCount: maximumCharacteristicSize! - 1)
                    writingQueueTotalChunks = chunks.count
                    writingQueue = chunks.makeIterator()
                    writingQueueChunkIndex = 0
                    drainWritingQueue()
                    update = true
                }

            case .sendingResponse:
                if machinePendingState == .complete {
                    machineState = machinePendingState
                }

            /// L2CAP flow.
            case .l2capAwaitRequest:
                if machinePendingState == .l2capRequestReceived {
                    machineState = machinePendingState
                    callback.callback(message: MDocBLECallback.message(incomingMessageBuffer))
                    incomingMessageBuffer = Data()
                }

            /// The request has been received, we're waiting for the user to respond to the selective diclosure
            /// dialog.
            case .l2capRequestReceived:
                if machinePendingState == .l2capSendingResponse {
                    machineState = machinePendingState
                    activeStream?.send(data: outgoingMessageBuffer)
                    machinePendingState = .l2capSendingResponse
                    update = true
                }

            case .l2capSendingResponse:
                if machinePendingState == .complete {
                    machineState = machinePendingState
                }

                //

            case .fatalError: // Something went wrong.
                machineState = .halted
                machinePendingState = .halted

            case .complete: // Transfer complete.
                break

            case .halted: // Transfer incomplete, but we gave up.
                break
            }
        }
    }

    func disconnectFromDevice(session: MdlPresentationSession) {
        let message: Data
        do {
            message = try session.terminateSession()
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
        if let peripheral = peripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
    }

    func writeOutgoingValue(data: Data) {
        outgoingMessageBuffer = data
        switch machineState {
        case .requestReceived:
            machinePendingState = .sendingResponse

        case .l2capRequestReceived:
            machinePendingState = .l2capSendingResponse

        default:
            print("Unexpected write in state \(machineState)")
        }
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
                callback.callback(message: .uploadProgress(writingQueueChunkIndex, writingQueueTotalChunks))
                peripheral?.writeValue(_: chunk,
                                       for: writeCharacteristic!,
                                       type: CBCharacteristicWriteType.withoutResponse)
                if firstByte == 0x00 {
                    machinePendingState = .complete
                }
            } else {
                callback.callback(message: .uploadProgress(writingQueueTotalChunks, writingQueueTotalChunks))
                writingQueue = nil
                machinePendingState = .complete
            }
        }
    }

    /// Verify that a characteristic matches what is required of it.
    private func getCharacteristic(list: [CBCharacteristic],
                                   uuid: CBUUID, properties: [CBCharacteristicProperties],
                                   required: Bool) throws -> CBCharacteristic? {
        let chName = MDocCharacteristicNameFromUUID(uuid)

        if let candidate = list.first(where: { $0.uuid == uuid }) {
            for prop in properties where !candidate.properties.contains(prop) {
                let propName = MDocCharacteristicPropertyName(prop)
                if required {
                    throw CharacteristicsError.missingMandatoryProperty(name: propName, characteristicName: chName)
                } else {
                    return nil
                }
            }
            return candidate
        } else {
            if required {
                throw CharacteristicsError.missingMandatoryCharacteristic(name: chName)
            } else {
                return nil
            }
        }
    }

    /// Check that the reqiured characteristics are available with the required properties.
    func processCharacteristics(peripheral: CBPeripheral, characteristics: [CBCharacteristic]) throws {
        stateCharacteristic = try getCharacteristic(list: characteristics,
                                                    uuid: readerStateCharacteristicId,
                                                    properties: [.notify, .writeWithoutResponse],
                                                    required: true)

        writeCharacteristic = try getCharacteristic(list: characteristics,
                                                    uuid: readerClient2ServerCharacteristicId,
                                                    properties: [.writeWithoutResponse],
                                                    required: true)

        readCharacteristic = try getCharacteristic(list: characteristics,
                                                   uuid: readerServer2ClientCharacteristicId,
                                                   properties: [.notify],
                                                   required: true)

        if let readerIdent = try getCharacteristic(list: characteristics,
                                                   uuid: readerIdentCharacteristicId,
                                                   properties: [.read],
                                                   required: true) {
            peripheral.readValue(for: readerIdent)
        }

        l2capCharacteristic = try getCharacteristic(list: characteristics,
                                                    uuid: readerL2CAPCharacteristicId,
                                                    properties: [.read],
                                                    required: false)

//       iOS controls MTU negotiation. Since MTU is just a maximum, we can use a lower value than the negotiated value.
//       18013-5 expects an upper limit of 515 MTU, so we cap at this even if iOS negotiates a higher value.
//
//       maximumWriteValueLength() returns the maximum characteristic size, which is 3 less than the MTU.
        let negotiatedMaximumCharacteristicSize = peripheral.maximumWriteValueLength(for: .withoutResponse)
        maximumCharacteristicSize = min(negotiatedMaximumCharacteristicSize - 3, 512)
    }

    /// Process incoming data from a peripheral. This handles incoming data from any and all characteristics (though not
    /// the L2CAP stream...), so we hit this call multiple times from several angles, at least in the original flow.
    func processData(peripheral: CBPeripheral, characteristic: CBCharacteristic) throws {
        if var data = characteristic.value {
            print("Processing \(data.count) bytes for \(MDocCharacteristicNameFromUUID(characteristic.uuid)) → ",
                  terminator: "")
            switch characteristic.uuid {
            /// Transfer indicator.
            case readerStateCharacteristicId:
                if data.count != 1 {
                    throw DataError.invalidStateLength
                }
                switch data[0] {
                case 0x02:
                    callback.callback(message: .done)
                    disconnect()
                case let byte:
                    throw DataError.unknownState(byte: byte)
                }

            /// Incoming request.
            case readerServer2ClientCharacteristicId:
                let firstByte = data.popFirst()
                incomingMessageBuffer.append(data)
                switch firstByte {
                case .none:
                    throw DataError.noData(characteristic: characteristic.uuid)

                case 0x00: // end
                    print("End")
                    machinePendingState = .requestReceived

                case 0x01: // partial
                    print("Chunk")
                    // TODO: check length against MTU

                case let .some(byte):
                    throw DataError.unknownDataTransferPrefix(byte: byte)
                }

            /// Ident check.
            case readerIdentCharacteristicId:
                // Looks like this should just happen after discovering characteristics
                print("Ident")
                // TODO: Presumably we should be doing something with the ident value; probably handing it
                // to the callback to see if the caller likes it.
                machinePendingState = .awaitRequest

            /// L2CAP channel ID.
            case readerL2CAPCharacteristicId:
                print("PSM: ", terminator: "")
                if data.count == 2 {
                    let psm = data.uint16
                    print("\(psm)")
                    channelPSM = psm
                    peripheral.openL2CAPChannel(psm)
                    machinePendingState = .l2capAwaitRequest
                }
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
    /// Handle a state change in the central manager.
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            machinePendingState = .hardwareOn
        } else {
            callback.callback(message: .error(.bluetooth(central)))
        }
    }

    /// Handle discovering a peripheral.
    func centralManager(_: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData _: [String: Any],
                        rssi _: NSNumber) {
        print("Discovered peripheral")
        peripheral.delegate = self
        self.peripheral = peripheral
        centralManager?.connect(peripheral, options: nil)
        machinePendingState = .peripheralDiscovered
    }

    /// Handle connecting to a peripheral.
    func centralManager(_: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([serviceUuid])
        machinePendingState = .checkPeripheral
    }
}

extension MDocHolderBLECentral: CBPeripheralDelegate {
    /// Handle discovery of peripheral services.
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if error != nil {
            callback.callback(
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

    /// Handle discovery of characteristics for a peripheral service.
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if error != nil {
            callback.callback(
                message: .error(.peripheral("Error discovering characteristics: \(error!.localizedDescription)"))
            )
            return
        }
        if let characteristics = service.characteristics {
            print("Discovered characteristics")
            do {
                try processCharacteristics(peripheral: peripheral, characteristics: characteristics)
            } catch {
                callback.callback(message: .error(.peripheral("\(error)")))
                centralManager?.cancelPeripheralConnection(peripheral)
            }
        }
    }

    /// Handle a characteristic value being updated.
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        do {
            try processData(peripheral: peripheral, characteristic: characteristic)
        } catch {
            callback.callback(message: .error(.peripheral("\(error)")))
            centralManager?.cancelPeripheralConnection(peripheral)
        }
    }

    /// Notifies that the peripheral write buffer has space for more chunks.
    /// This is called after the buffer gets filled to capacity, and then has space again.
    ///
    /// Only available on iOS 11 and up.
    func peripheralIsReady(toSendWriteWithoutResponse _: CBPeripheral) {
        drainWritingQueue()
    }

    func peripheral(_: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        if let error = error {
            print("Error opening l2cap channel - \(error.localizedDescription)")
            return
        }

        if let channel = channel {
            activeStream = MDocHolderBLECentralConnection(delegate: self, channel: channel)
        }
    }
}

extension MDocHolderBLECentral: CBPeripheralManagerDelegate {
    /// Handle peripheral manager state change.
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

extension MDocHolderBLECentral: MDocHolderBLECentralConnectionDelegate {
    func request(_ data: Data) {
        incomingMessageBuffer = data
        machinePendingState = .l2capRequestReceived
    }

    func sendUpdate(bytes: Int, total: Int, fraction _: Double) {
        callback.callback(message: .uploadProgress(bytes, total))
    }

    func sendComplete() {
        machinePendingState = .complete
    }

    func connectionEnd() {}
}
