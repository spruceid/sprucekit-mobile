import Algorithms
import CoreBluetooth
import Foundation
import SpruceIDMobileSdkRs

// NOTE: https://blog.valerauko.net/2024/03/24/some-notes-on-ios-ble/
// error 431 is "peer requested disconnect"
// error 436 is "local requested disconnect"

class MDocReaderBLEPeripheral: NSObject {
    enum MachineState {
        case initial, hardwareOn, servicePublished
        case fatalError, complete, halted
        case l2capRead, l2capAwaitChannelPublished, l2capChannelPublished
        case l2capStreamOpen, l2capSendingRequest, l2capAwaitingResponse
        case stateSubscribed, awaitRequestStart, sendingRequest, awaitResponse
    }

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
    var requestSent = false
    var maximumCharacteristicSize: Int?
    var writingQueueTotalChunks: Int?
    var writingQueueChunkIndex: Int?
    var writingQueue: IndexingIterator<ChunksOfCountCollection<Data>>?

    var activeStream: MDocReaderBLEPeripheralConnection?

    /// If this is `true`, we offer an L2CAP characteristic and set up an L2CAP stream.  If it is `false` we do neither
    /// of these things, and use the old flow.
    var useL2CAP = true

    private var channelPSM: UInt16? {
        didSet {
            updatePSM()
        }
    }

    var machineState = MachineState.initial
    var machinePendingState = MachineState.initial {
        didSet {
            updateState()
        }
    }

    init(callback: MDocReaderBLEDelegate, serviceUuid: CBUUID, request: Data, bleIdent: Data) {
        self.serviceUuid = serviceUuid
        self.callback = callback
        self.bleIdent = bleIdent
        requestData = request
        incomingMessageBuffer = Data()
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self,
                                                queue: nil,
                                                options: [CBPeripheralManagerOptionShowPowerAlertKey: true])
    }

    /// Update the state machine.
    private func updateState() {
        var update = true

        while update {
            print(machineState == machinePendingState
                    ? "「\(machineState)」"
                    : "「\(machineState) → \(machinePendingState)」")
            update = false

            switch machineState {
            /// Core.
            case .initial: // Object just initialized, hardware not ready.
                if machinePendingState == .hardwareOn {
                    machineState = .hardwareOn
                    update = true
                }

            case .hardwareOn: // Hardware is ready.
                setupService()
                peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUuid]])
                machineState = .servicePublished
                machinePendingState = .servicePublished
                update = true

            case .servicePublished: // Characteristics set up, we're publishing our service.
                if machinePendingState == .l2capRead {
                    machineState = machinePendingState
                    update = true
                } else if machinePendingState == .stateSubscribed {
                    machineState = machinePendingState
                    update = true
                }

            case .fatalError: // Something went wrong.
                machineState = .halted
                machinePendingState = .halted
            case .complete: // Transfer complete.
                disconnect()

            case .halted: // Transfer incomplete, but we gave up.
                break

            /// L2CAP flow.
            case .l2capRead: // We have a read on our L2CAP characteristic, start L2CAP flow.
                machineState = .l2capAwaitChannelPublished

                // Setting the encryption flag here appears to break at least some Android devices; if `withEncryption`
                // is set, when the Android device calls `.connect()` on the socket it throws a "resource not
                // available" exception.  The data is already encrypted, so I'm setting it to false, and making Android
                // recover from the exception and fall back to the old flow if necessary as well.  Support code for
                // failover is in the next two states, below.

                peripheralManager.publishL2CAPChannel(withEncryption: false)
                update = true

            case .l2capAwaitChannelPublished:
                if machinePendingState == .l2capChannelPublished {
                    machineState = machinePendingState
                } else if machinePendingState == .stateSubscribed {

                    // Failover case for Android; we could get this here, or in the published state.  Android devices
                    // may be unable to connect() on an L2CAP socket (see the comment in .l2capRead, above), and if
                    // they do they "switch tracks" to the old flow.  We need to notice that's happened and support
                    // it here.

                    print("Failover to non-L2CAP flow.")

                    if let psm = channelPSM {
                        peripheralManager.unpublishL2CAPChannel(psm)
                    }
                    machineState = machinePendingState
                    update = true
                }

            case .l2capChannelPublished:
                if machinePendingState == .l2capStreamOpen {
                    machineState = machinePendingState
                    update = true
                } else if machinePendingState == .stateSubscribed {

                    // See the comments in .l2capRead and .l2capAwaitChannelPublished, above.

                    print("Failover to non-L2CAP flow.")

                    if let psm = channelPSM {
                        peripheralManager.unpublishL2CAPChannel(psm)
                    }
                    machineState = machinePendingState
                    update = true
                }

            case .l2capStreamOpen: // An L2CAP stream is opened.
                if !requestSent {
                    // We occasionally seem to get a transient condition where the stream gets opened
                    // more than once; locally, I've seen the stream open 10 times in a row, and with
                    // the non-framed transport for data, that means we send the request 10 times, the
                    // far side reads that as a single request, and errors out.  The requestSent bool
                    // is to keep this from happening.
                    activeStream?.send(data: requestData)
                    requestSent = true
                }
                machineState = .l2capSendingRequest
                machinePendingState = .l2capSendingRequest
                update = true

            case .l2capSendingRequest: // The request is being sent over the L2CAP stream.
                print("l2capSendingRequest...  machinePendingState = \(machinePendingState)")
                if machinePendingState == .l2capAwaitingResponse {
                    machineState = machinePendingState
                    update = true
                } else if machinePendingState == .complete {
                    machineState = machinePendingState
                    callback.callback(message: MDocReaderBLECallback.message(incomingMessageBuffer))
                    update = true
                }

            case .l2capAwaitingResponse: // The request is sent, the response is (hopefully) coming in.
                if machinePendingState == .complete {
                    machineState = machinePendingState
                    callback.callback(message: MDocReaderBLECallback.message(incomingMessageBuffer))
                    update = true
                }

            /// Original flow.
            case .stateSubscribed: // We have a subscription to our State characteristic, start original flow.
                // This will trigger MobileSdk to send 0x01 to start the exchange
                peripheralManager.updateValue(bleIdent, for: identCharacteristic!, onSubscribedCentrals: nil)

                // I think the updateValue() below is out of spec; 8.3.3.1.1.5 says we wait for a write without
                // response of 0x01 to State, but that's supposed to come from the holder to indicate it's ready
                // for us to initiate.

                // This will trigger wallet-sdk-kt to send 0x01 to start the exchange
                // peripheralManager.updateValue(Data([0x01]), for: self.stateCharacteristic!,
                //                               onSubscribedCentrals: nil)

                machineState = .awaitRequestStart
                machinePendingState = .awaitRequestStart

            case .awaitRequestStart: // We've let the holder know we're ready, waiting for their ack.
                if machinePendingState == .sendingRequest {
                    writeOutgoingValue(data: requestData)
                    machineState = .sendingRequest
                }

            case .sendingRequest:
                if machinePendingState == .awaitResponse {
                    machineState = .awaitResponse
                }

            case .awaitResponse:
                if machinePendingState == .complete {
                    machineState = .complete
                    update = true
                }
            }
        }
    }

    func setupService() {
        let service = CBMutableService(type: serviceUuid, primary: true)
        // CBUUIDClientCharacteristicConfigurationString only returns "2902"
        //   let clientDescriptor = CBMutableDescriptor(type: CBUUID(string: "00002902-0000-1000-8000-00805f9b34fb"),
        //                                              value: Data([0x00, 0x00])) as CBDescriptor
        // wallet-sdk-kt isn't using write without response...
        stateCharacteristic = CBMutableCharacteristic(type: readerStateCharacteristicId,
                                                      properties: [.notify, .writeWithoutResponse, .write],
                                                      value: nil,
                                                      permissions: [.writeable])
        // for some reason this seems to drop all other descriptors
        // self.stateCharacteristic!.descriptors = [clientDescriptor] + (self.stateCharacteristic!.descriptors ?? [] )
        // self.stateCharacteristic!.descriptors?.insert(clientDescriptor, at: 0)
        // wallet-sdk-kt isn't using write without response...
        readCharacteristic = CBMutableCharacteristic(type: readerClient2ServerCharacteristicId,
                                                     properties: [.writeWithoutResponse, .write],
                                                     value: nil,
                                                     permissions: [.writeable])
        writeCharacteristic = CBMutableCharacteristic(type: readerServer2ClientCharacteristicId,
                                                      properties: [.notify],
                                                      value: nil,
                                                      permissions: [.readable, .writeable])
        // self.writeCharacteristic!.descriptors = [clientDescriptor] + (self.writeCharacteristic!.descriptors ?? [] )
        // self.writeCharacteristic!.descriptors?.insert(clientDescriptor, at: 0)
        identCharacteristic = CBMutableCharacteristic(type: readerIdentCharacteristicId,
                                                      properties: [.read],
                                                      value: bleIdent,
                                                      permissions: [.readable])
        // wallet-sdk-kt is failing if this is present
        if useL2CAP {
            // 18013-5 doesn't require .indicate, but without it we don't seem to be able to propagate the PSM
            // through to central.
            l2capCharacteristic = CBMutableCharacteristic(type: readerL2CAPCharacteristicId,
                                                          properties: [.read, .indicate, .notify],
                                                          value: nil,
                                                          permissions: [.readable])

            if let stateC = stateCharacteristic,
               let readC = readCharacteristic,
               let writeC = writeCharacteristic,
               let identC = identCharacteristic,
               let l2capC = l2capCharacteristic {
                service.characteristics = (service.characteristics ?? []) + [stateC, readC, writeC, identC, l2capC]
            }
        } else {
            if let stateC = stateCharacteristic,
               let readC = readCharacteristic,
               let writeC = writeCharacteristic,
               let identC = identCharacteristic {
                service.characteristics = (service.characteristics ?? []) + [stateC, readC, writeC, identC]
            }
        }
        peripheralManager.add(service)
    }

    func disconnect() {
        print("Disconnecting...")
        peripheralManager.stopAdvertising()
        activeStream?.close()

        if let psm = channelPSM {
            peripheralManager.unpublishL2CAPChannel(psm)
            channelPSM = nil
        }

        peripheralManager.removeAllServices()

        incomingMessageBuffer = Data()
        incomingMessageIndex = 0
        requestSent = false

        machineState = .initial
        machinePendingState = .initial

        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: nil,
            options: [CBPeripheralManagerOptionShowPowerAlertKey: true]
        )
        print("✅ Disconnected")
    }

    /// Write the request using the old flow.
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
                peripheralManager?.updateValue(chunk, for: writeCharacteristic!, onSubscribedCentrals: nil)

                if firstByte == 0x00 {
                    machinePendingState = .awaitResponse
                }
            } else {
                writingQueue = nil
                machinePendingState = .awaitResponse
            }
        }
    }

    /// Process incoming data.
    func processData(central _: CBCentral, characteristic: CBCharacteristic, value: Data?) throws {
        if var data = value {
            let name = MDocCharacteristicNameFromUUID(characteristic.uuid)
            print("Processing \(data.count) bytes of data for \(name) → ", terminator: "")
            switch characteristic.uuid {
            case readerClient2ServerCharacteristicId:
                let firstByte = data.popFirst()
                incomingMessageBuffer.append(data)
                switch firstByte {
                case .none:
                    print("Nothing?")
                    throw DataError.noData(characteristic: characteristic.uuid)
                case 0x00: // end
                    print("End")
                    callback.callback(message: MDocReaderBLECallback.message(incomingMessageBuffer))
                    incomingMessageBuffer = Data()
                    incomingMessageIndex = 0
                    machinePendingState = .complete
                    return
                case 0x01: // partial
                    print("Chunk")
                    incomingMessageIndex += 1
                    callback.callback(message: .downloadProgress(incomingMessageIndex))
                    // TODO: check length against MTU
                    return
                case let .some(byte):
                    print("Unexpected byte \(String(format: "$%02X", byte))")
                    throw DataError.unknownDataTransferPrefix(byte: byte)
                }

            case readerStateCharacteristicId:
                print("State")
                if data.count != 1 {
                    throw DataError.invalidStateLength
                }
                switch data[0] {
                case 0x01:
                    machinePendingState = .sendingRequest
                case let byte:
                    throw DataError.unknownState(byte: byte)
                }

            case readerL2CAPCharacteristicId:
                print("L2CAP")
                machinePendingState = .l2capRead
                return

            case let uuid:
                print("Unexpected UUID")
                throw DataError.unknownCharacteristic(uuid: uuid)
            }
        } else {
            throw DataError.noData(characteristic: characteristic.uuid)
        }
    }

    /// Update the channel PSM.
    private func updatePSM() {
        l2capCharacteristic?.value = channelPSM?.data

        if let l2capC = l2capCharacteristic {
            let value = channelPSM?.data ?? Data()

            l2capC.value = value
            peripheralManager.updateValue(value, for: l2capC, onSubscribedCentrals: nil)
        }
    }
}

/// Peripheral manager delegate functions.
extension MDocReaderBLEPeripheral: CBPeripheralManagerDelegate {
    /// Handle the peripheral updating state.
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            machinePendingState = .hardwareOn
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

    /// Handle space available for sending.  This is part of the send loop for the old (non-L2CAP) flow.
    func peripheralManagerIsReady(toUpdateSubscribers _: CBPeripheralManager) {
        drainWritingQueue()
    }

    /// Handle incoming subscriptions.
    func peripheralManager(_: CBPeripheralManager,
                           central _: CBCentral,
                           didSubscribeTo characteristic: CBCharacteristic) {
        callback.callback(message: .connected)
        peripheralManager?.stopAdvertising()
        switch characteristic.uuid {
        case l2capCharacteristic: // If we get this, we're in the L2CAP flow.
            // TODO: If this gets hit after a subscription to the State characteristic, something has gone wrong;
            // the holder should choose one flow or the other.  We have options here:
            //
            // - ignore the corner case -- what the code is doing now, not ideal
            // - error out -- the holder is doing something screwy, we want no part of it
            // - try to adapt -- send the data a second time, listen on both L2CAP and normal - probably a bad idea;
            //   it will make us mildly more tolerant of out-of-spec holders, but may increase our attack surface
            machinePendingState = .l2capRead

        case readerStateCharacteristicId: // If we get this, we're in the original flow.
            // TODO: See the comment block in the L2CAP characteristic, above; only one of these can be valid for
            // a given exchange.
            machinePendingState = .stateSubscribed

        case _:
            return
        }
    }

    /// Handle read requests.
    func peripheralManager(_: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        print("Received read request for \(MDocCharacteristicNameFromUUID(request.characteristic.uuid))")

        // Since there is no callback for MTU on iOS we will grab it here.
        maximumCharacteristicSize = min(request.central.maximumUpdateValueLength, 512)

        if request.characteristic.uuid == readerIdentCharacteristicId {
            peripheralManager.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == readerL2CAPCharacteristicId {
            peripheralManager.respond(to: request, withResult: .success)
            machinePendingState = .l2capRead
        } else {
            let name = MDocCharacteristicNameFromUUID(request.characteristic.uuid)
            callback.callback(message: .error(.server("Read on unexpected characteristic with UUID \(name)")))
        }
    }

    /// Handle write requests.
    func peripheralManager(_: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            // Since there is no callback for MTU on iOS we will grab it here.
            maximumCharacteristicSize = min(request.central.maximumUpdateValueLength, 512)

            do {
                try processData(central: request.central, characteristic: request.characteristic, value: request.value)
                // This can be removed, or return an error, once wallet-sdk-kt is fixed and uses withoutResponse writes
                if request.characteristic.properties.contains(.write) {
                    peripheralManager.respond(to: request, withResult: .success)
                }
            } catch {
                callback.callback(message: .error(.server("\(error)")))
                peripheralManager?.updateValue(Data([0x02]), for: stateCharacteristic!, onSubscribedCentrals: nil)
            }
        }
    }

    /// Handle an L2CAP channel being published.
    public func peripheralManager(_: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        if let error = error {
            print("Error publishing channel: \(error.localizedDescription)")
            return
        }
        print("Published channel \(PSM)")
        channelPSM = PSM
        machinePendingState = .l2capChannelPublished
    }

    /// Handle an L2CAP channel opening.
    public func peripheralManager(_: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        if let error = error {
            print("Error opening channel: \(error.localizedDescription)")
            return
        }

        if let channel = channel {
            activeStream = MDocReaderBLEPeripheralConnection(delegate: self, channel: channel)
        }
    }
}

/// L2CAP Stream delegate functions.
extension MDocReaderBLEPeripheral: MDocReaderBLEPeriConnDelegate {
    func streamOpen() {
        // This sometimes gets hit multiple times.
        if machinePendingState == .l2capChannelPublished {
            machinePendingState = .l2capStreamOpen
        }
    }

    func sentData(_ bytes: Int) {
        if bytes >= requestData.count {
            machinePendingState = .l2capAwaitingResponse
        }
    }

    func receivedData(_ data: Data) {
        incomingMessageBuffer = data
        machinePendingState = .complete
    }
}
