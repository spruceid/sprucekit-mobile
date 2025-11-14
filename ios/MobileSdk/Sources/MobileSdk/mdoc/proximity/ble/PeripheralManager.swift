import CoreBluetooth
import Foundation
import SpruceIDMobileSdkRs

/// Manages the BLE peripheral for an mdoc or reader.
class PeripheralManager: NSObject & Transport {
    private let participant: Participant
    private var serviceUuid: CBUUID?
    private let peripheralManager: CBPeripheralManager
    private let l2capUsage: L2CAPUsage
    private var l2capPsm: CBL2CAPPSM?

    private let characteristics: Characteristics

    private var state: InternalState = .initializing {
        didSet {
            let state: State
            switch self.state {
            case .initializing:
                state = .initializing
            case let .ready(ready):
                state = .ready(ready.details)
            case .connected:
                state = .connected
            case .disconnected:
                state = .disconnected
            case .error:
                state = .error
            }
            participant.peripheralDidUpdateState(state: state)
        }
    }

    private init(
        _ participant: Participant,
        _ uuid: UUID?,
        _ l2capUsage: L2CAPUsage,
        _ characteristics: Characteristics,
    ) {
        self.participant = participant
        self.l2capUsage = l2capUsage
        self.characteristics = characteristics
        peripheralManager = CBPeripheralManager(
            delegate: nil,
            queue: nil,
            options: [CBPeripheralManagerOptionShowPowerAlertKey: true]
        )
        super.init()
        peripheralManager.delegate = self
        if let uuid {
            serviceUuid = CBUUID(nsuuid: uuid)
        } else {
            print("holder supplied an invalid UUID")
            state = .error
        }
    }

    deinit {
        print("Shutting down PeripheralManager")
        if self.characteristics.state.value != connectionStateEnd {
            self.peripheralManager.updateValue(
                connectionStateEnd,
                for: self.characteristics.state,
                onSubscribedCentrals: nil
            )
        }

        if let l2capPsm {
            self.peripheralManager.unpublishL2CAPChannel(l2capPsm)
        }

        self.peripheralManager.removeAllServices()
        self.peripheralManager.stopAdvertising()
    }

    /// Setup the peripheral server for the reader: mdocCentralClientMode.
    convenience init(
        reader delegate: MdocProximityReader.DelegateWrapper,
        mdocCentralClientMode: CentralClientDetails,
        _ l2capUsage: L2CAPUsage,
    ) {
        self.init(
            .reader(delegate),
            UUID(uuidString: mdocCentralClientMode.serviceUuid),
            l2capUsage,
            Characteristics(reader: l2capUsage)
        )
    }

    /// Setup the peripheral server for the mdoc: mdocPeripheralServerMode.
    convenience init(mdoc delegate: MdocProximityPresentationManager.DelegateWrapper, _ l2capUsage: L2CAPUsage) {
        self.init(.mdoc(delegate), UUID(), l2capUsage, Characteristics(mdoc: l2capUsage))
    }

    enum State {
        /// Setting up the Peripheral service and characteristics.
        case initializing
        /// Waiting for the Central to connect.
        case ready(PeripheralServerDetails)
        /// The Central has connected.
        case connected
        /// The Central has disconnected.
        case disconnected
        /// An unrecoverable error occurred.
        case error
    }

    func send(message: Data) -> Bool {
        var accepted = false
        switch state {
        case let .connected(connected):
            if let l2cap = connected.l2capManager {
                print("sending message from Peripheral via L2CAP")
                l2cap.send(data: message)
                accepted = true
            } else {
                switch connected.gattOutbox.send(message: message) {
                case .accepted:
                    print("sending message from Peripheral via GATT")
                    startOrContinueGATTTransmission()
                    accepted = true
                case .busy:
                    print("attempted to send message while busy sending another message")
                }
            }
        default:
            print("attempted to send message during an invalid state: \(state)")
        }
        return accepted
    }
}

/// CBPeripheralManagerDelegate conformance.
extension PeripheralManager: CBPeripheralManagerDelegate {
    // Invoked when the state of the hardware is updated.
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            if case .initializing = state, let serviceUuid {
                if case .disableL2CAP = l2capUsage {
                    registerService(serviceUuid: serviceUuid)
                } else {
                    peripheral.publishL2CAPChannel(withEncryption: false)
                }
            }
        case .unsupported:
            print("BLE is not supported")
            state = .error
        case .unauthorized:
            participant.transportDelegate().required(action: .authorizeBluetoothForApp)
        case .poweredOff:
            if case .initializing = state {
                participant.transportDelegate().required(action: .turnOnBluetooth)
            } else {
                print("BLE powered off during session")
                state = .error
            }
        default:
            print("the CBPeripheralManager state updated without action: \(peripheral.state)")
        }
    }

    /// Invoked when the L2CAP channel was successfully published.
    func peripheralManager(
        _: CBPeripheralManager,
        didPublishL2CAPChannel PSM: CBL2CAPPSM,
        error: (any Error)?
    ) {
        guard case .initializing = state, let serviceUuid else {
            print("L2CAP channel published during invalid state: \(state)")
            return
        }

        if let error {
            print("an error occurred when publishing the L2CAP channel: \(error)")
            if case .forceL2CAP = l2capUsage {
                state = .error
            } else {
                print("continuing without L2CAP")
            }
        } else {
            print("the CBPeripheralManager L2CAP channel published: \(PSM)")
            l2capPsm = PSM
        }

        registerService(serviceUuid: serviceUuid)
    }

    func peripheralManager(_: CBPeripheralManager, didAdd _: CBService, error: (any Error)?) {
        if let error {
            print("an error occurred when adding the GATT service: \(error.localizedDescription)")
            state = .error
        }
        guard case .initializing = state, let serviceUuid else {
            print("GATT service added during invalid state: \(state)")
            return
        }

        peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUuid]])

        print("Peripheral started advertising...")

        state = .ready(Ready(
            details: PeripheralServerDetails(
                serviceUuid: serviceUuid.uuidString,
                // JW: As far as I can tell, Apple does not provide API access to the device address.
                bleDeviceAddress: nil
            )
        ))
    }

    func peripheralManager(_: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: (any Error)?) {
        if let channel = channel, error == nil {
            if case let .ready(ready) = state {
                print("the CBPeripheralManager L2CAP channel opened with '\(channel.peer.identifier)'")
                ready.openl2capChannels[channel.peer.identifier] = channel
            } else {
                print("the CBPeripheralManager L2CAP channel opened during an invalid state: \(state)")
            }
        } else {
            print("the CBPeripheralManager L2CAP channel failed to open:" +
                " \(error?.localizedDescription ?? "unknown error")")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if participant.l2capCharacteristic() == request.characteristic.uuid {
            request.value = l2capPsm?.toData()
            peripheral.respond(to: request, withResult: .success)
        } else if let identUuid = participant.identCharacteristic(), identUuid == request.characteristic.uuid {
            request.value = participant.identCharacteristicValue()
            peripheral.respond(to: request, withResult: .success)
        } else if participant.stateCharacteristic() == request.characteristic.uuid {
            request.value = characteristics.state.value
            peripheral.respond(to: request, withResult: .success)
        } else {
            print("received an unauthorized attempt by '\(request.central.identifier)' " +
                "to read characteristic: \(request.characteristic.uuid)")
            peripheral.respond(to: request, withResult: .readNotPermitted)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if participant.stateCharacteristic() == request.characteristic.uuid {
                handleStateCharacteristicUpdate(request)
            } else if participant.client2ServerCharacteristic() == request.characteristic.uuid {
                handleClient2ServerCharacteristicUpdate(request)
            } else {
                print("received an unauthorized attempt by '\(request.central.identifier)' " +
                    "to write characteristic: \(request.characteristic.uuid)")
                peripheral.respond(to: request, withResult: .writeNotPermitted)
            }
        }
    }

    // Invoked after a failed attempt to update the value of a characteristic, when the peripheral is
    // ready again to update the value.
    func peripheralManagerIsReady(toUpdateSubscribers _: CBPeripheralManager) {
        startOrContinueGATTTransmission()
    }
}

/// Operations.
private extension PeripheralManager {
    class Ready {
        let details: PeripheralServerDetails

        var openl2capChannels: [UUID: CBL2CAPChannel]

        init(details: PeripheralServerDetails, openl2capChannels: [UUID: CBL2CAPChannel] = [:]) {
            self.details = details
            self.openl2capChannels = openl2capChannels
        }
    }

    struct Connected {
        let central: CBCentral,
            gattInbox: GATTInbox,
            gattOutbox: GATTOutbox,
            l2capManager: L2CAPManager?
    }

    enum InternalState {
        /// Setting up the Peripheral service and characteristics.
        case initializing
        /// Waiting for the Central to connect.
        case ready(Ready)
        /// The Central has connected.
        case connected(Connected)
        /// The Central has disconnected.
        case disconnected
        /// An unrecoverable error occurred.
        case error
    }

    func registerService(serviceUuid: CBUUID) {
        let service = characteristics.service(uuid: serviceUuid)
        peripheralManager.add(service)
    }

    func handleStateCharacteristicUpdate(_ request: CBATTRequest) {
        guard let updatedValue = request.value else {
            print("no data was written to state characteristic")
            return
        }
        characteristics.state.value = updatedValue
        switch updatedValue {
        case connectionStateStart:
            guard case let .ready(ready) = state else {
                print("'start' was written to state characteristic during an invalid state: \(state)")
                return
            }
            print("connected to \(request.central.identifier)")
            let central = request.central
            let mtu = min(request.central.maximumUpdateValueLength, 515)
            let l2capManager: L2CAPManager? = ready.openl2capChannels[central.identifier].map { channel in
                L2CAPManager(channel: channel, delegate: self.participant.transportDelegate())
            }
            state = .connected(Connected(
                central: central,
                gattInbox: GATTInbox(delegate: participant.transportDelegate()),
                gattOutbox: GATTOutbox(mtu: mtu, delegate: participant.transportDelegate()),
                l2capManager: l2capManager,
            ))
        case connectionStateEnd:
            state = .disconnected
        default:
            print("an unrecognized value was written to state characteristic: \(updatedValue)")
        }
    }

    func handleClient2ServerCharacteristicUpdate(_ request: CBATTRequest) {
        guard case let .connected(connected) = state else {
            print("the client wrote to client2Server characteristic during an invalid state: \(state)")
            return
        }
        guard let value = request.value else {
            print("no data was written to client2Server characteristic")
            return
        }
        connected.gattInbox.accept(chunk: value)
    }

    func startOrContinueGATTTransmission() {
        guard case let .connected(connected) = state else {
            return
        }
        if let chunk = connected.gattOutbox.nextChunk() {
            if peripheralManager.updateValue(
                chunk.data,
                for: characteristics.server2Client,
                onSubscribedCentrals: [connected.central]
            ) {
                chunk.commit()
                startOrContinueGATTTransmission()
            }
        }
    }
}

/// Characteristics and service setup.
private extension PeripheralManager {
    class Characteristics {
        let state: CBMutableCharacteristic
        let client2Server: CBMutableCharacteristic
        let server2Client: CBMutableCharacteristic
        let l2cap: CBMutableCharacteristic?
        let ident: CBMutableCharacteristic?

        init(mdoc l2cap: L2CAPUsage) {
            state = MdocCharacteristicFactory.state()
            client2Server = MdocCharacteristicFactory.client2Server()
            server2Client = MdocCharacteristicFactory.server2Client()
            ident = nil
            switch l2cap {
            case .disableL2CAP:
                self.l2cap = nil
            default:
                self.l2cap = MdocCharacteristicFactory.l2cap()
            }
        }

        init(reader l2cap: L2CAPUsage) {
            state = ReaderCharacteristicFactory.state()
            client2Server = ReaderCharacteristicFactory.client2Server()
            server2Client = ReaderCharacteristicFactory.server2Client()
            ident = ReaderCharacteristicFactory.ident()
            switch l2cap {
            case .disableL2CAP:
                self.l2cap = nil
            default:
                self.l2cap = ReaderCharacteristicFactory.l2cap()
            }
        }

        func service(uuid: CBUUID) -> CBMutableService {
            let service = CBMutableService(type: uuid, primary: true)
            service.characteristics = [state, server2Client, client2Server]
            l2cap.map { service.characteristics?.append($0) }
            ident.map { service.characteristics?.append($0) }
            return service
        }
    }

    /// The GATT characteristics used by the Mdoc when it is the peripheral.
    private class MdocCharacteristicFactory {
        static func state() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Mdoc.state,
                properties: [.notify, .writeWithoutResponse],
                value: nil,
                permissions: [.writeable, .readable]
            )
        }

        static func client2Server() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Mdoc.client2Server,
                properties: [.writeWithoutResponse],
                value: nil,
                permissions: [.writeable]
            )
        }

        static func server2Client() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Mdoc.server2Client,
                properties: [.notify],
                value: nil,
                permissions: [.readable]
            )
        }

        static func l2cap() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Mdoc.l2cap,
                properties: [.read],
                value: nil,
                permissions: [.readable]
            )
        }
    }

    /// The GATT characteristics used by the Reader when it is the peripheral.
    private class ReaderCharacteristicFactory {
        static func state() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Reader.state,
                properties: [.notify, .writeWithoutResponse],
                value: nil,
                permissions: [.writeable, .readable]
            )
        }

        static func client2Server() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Reader.client2Server,
                properties: [.writeWithoutResponse],
                value: nil,
                permissions: [.writeable]
            )
        }

        static func server2Client() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Reader.server2Client,
                properties: [.notify],
                value: nil,
                permissions: [.readable]
            )
        }

        static func l2cap() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Reader.l2cap,
                properties: [.read],
                value: nil,
                permissions: [.readable]
            )
        }

        static func ident() -> CBMutableCharacteristic {
            CBMutableCharacteristic(
                type: CharacteristicUuids.Reader.ident,
                properties: [.read],
                value: nil,
                permissions: [.readable]
            )
        }
    }
}

private enum Participant {
    case mdoc(MdocProximityPresentationManager.DelegateWrapper)
    case reader(MdocProximityReader.DelegateWrapper)

    func transportDelegate() -> TransportCallback {
        switch self {
        case let .mdoc(inner):
            inner
        case let .reader(inner):
            inner
        }
    }

    func stateCharacteristic() -> CBUUID {
        switch self {
        case .mdoc:
            CharacteristicUuids.Mdoc.state
        case .reader:
            CharacteristicUuids.Reader.state
        }
    }

    func server2ClientCharacteristic() -> CBUUID {
        switch self {
        case .mdoc:
            CharacteristicUuids.Mdoc.server2Client
        case .reader:
            CharacteristicUuids.Reader.server2Client
        }
    }

    func client2ServerCharacteristic() -> CBUUID {
        switch self {
        case .mdoc:
            CharacteristicUuids.Mdoc.client2Server
        case .reader:
            CharacteristicUuids.Reader.client2Server
        }
    }

    func l2capCharacteristic() -> CBUUID {
        switch self {
        case .mdoc:
            CharacteristicUuids.Mdoc.l2cap
        case .reader:
            CharacteristicUuids.Reader.l2cap
        }
    }

    func identCharacteristic() -> CBUUID? {
        switch self {
        case .mdoc:
            nil
        case .reader:
            CharacteristicUuids.Reader.ident
        }
    }

    func peripheralDidUpdateState(state: PeripheralManager.State) {
        switch self {
        case let .mdoc(delegate):
            delegate.peripheralDidUpdate(state: state)
        case let .reader(delegate):
            delegate.peripheralDidUpdate(state: state)
        }
    }

    func identCharacteristicValue() -> Data? {
        switch self {
        case .mdoc:
            nil
        case let .reader(delegate):
            delegate.bleIdent()
        }
    }
}
