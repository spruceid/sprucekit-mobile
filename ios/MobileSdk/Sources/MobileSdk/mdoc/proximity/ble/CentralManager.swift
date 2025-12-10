import CoreBluetooth
import Foundation
import SpruceIDMobileSdkRs

/// Manages the BLE central for an mdoc or reader.
///
/// Use `mdoc()` or `reader()` to construct the appropriate manager.
class CentralManager: NSObject & Transport {
    private let participant: Participant
    private var serviceUuid: CBUUID?
    private let centralManager: CBCentralManager
    private let l2capUsage: L2CAPUsage

    private var state: InternalState = .initializing {
        didSet {
            let state: State
            switch self.state {
            case .initializing:
                state = .initializing
            case let .scanning(details):
                state = .scanning(details)
            case .connecting, .serviceDiscovery, .awaitingIdentUpdate, .awaitingL2CAPUpdate,
                 .openingL2CAPChannel, .awaitingGATTConnection:
                state = .connecting
            case .connectedViaGATT, .connectedViaL2CAP:
                state = .connected
            case .disconnected:
                state = .disconnected
            case .error:
                state = .error
            }
            participant.centralDidUpdate(state: state)
        }
    }

    // Stored at the top-level only to enable cleanup.
    private var peripheral: CBPeripheral?
    private var stateCharacteristic: CBCharacteristic?

    private init(delegate: Participant, _ uuid: UUID?, _ l2capUsage: L2CAPUsage) {
        participant = delegate
        self.l2capUsage = l2capUsage
        centralManager = CBCentralManager(
            delegate: nil,
            queue: nil,
            options: [CBCentralManagerOptionShowPowerAlertKey: true]
        )
        super.init()
        centralManager.delegate = self
        if let uuid {
            serviceUuid = CBUUID(nsuuid: uuid)
        } else {
            print("holder supplied an invalid UUID")
            state = .error
        }
    }

    deinit {
        print("Shutting down CentralManager")
        self.centralManager.stopScan()
        if let peripheral {
            self.centralManager.cancelPeripheralConnection(peripheral)
        }
        if let stateCharacteristic, let peripheral {
            if case .disconnected = state {} else {
                peripheral.writeValue(connectionStateEnd, for: stateCharacteristic, type: .withoutResponse)
            }
        }
    }

    /// Setup the central client for the reader: mdocPeripheralServerMode.
    convenience init(
        reader: MdocProximityReader.DelegateWrapper,
        mdocPeripheralServerMode: PeripheralServerDetails,
        _ l2capUsage: L2CAPUsage,
    ) {
        self.init(delegate: .reader(reader), UUID(uuidString: mdocPeripheralServerMode.serviceUuid), l2capUsage)
    }

    /// Setup the central client for the mdoc: mdocCentralClientMode.
    convenience init(mdoc: MdocProximityPresentationManager.DelegateWrapper, _ l2capUsage: L2CAPUsage) {
        self.init(delegate: .mdoc(mdoc), UUID(), l2capUsage)
    }

    enum State {
        case initializing
        /// Scanning for the peripheral.
        case scanning(CentralClientDetails)
        /// Connecting to the found peripheral.
        case connecting
        /// Connected to the Peripheral.
        case connected
        /// Disconnected from the Peripheral.
        case disconnected
        /// An unrecoverable error occurred.
        case error
    }

    func send(message: Data) -> Bool {
        var accepted = false
        switch state {
        case let .connectedViaL2CAP(_, _, l2capManager):
            print("sending message from Central via L2CAP")
            l2capManager.send(data: message)
            accepted = true
        case let .connectedViaGATT(gatt):
            print("sending message from Central via GATT")
            switch gatt.outbox.send(message: message) {
            case .accepted:
                startOrContinueGATTTransmission()
                accepted = true
            case .busy:
                print("attempted to send message while busy sending another message")
            }
        default:
            print("attempted to send message during an invalid state: \(state)")
        }
        return accepted
    }
}

extension CentralManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
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
        case .poweredOn:
            if case .initializing = state, let serviceUuid {
                // TODO: Once isomdl supports L2CAP PSM sharing in Device Engagement, use it here to try to shortcut
                // the L2CAP channel establishment.
                state = .scanning(
                    details: CentralClientDetails(serviceUuid: serviceUuid.uuidString)
                )
                central.scanForPeripherals(withServices: [serviceUuid])
            }
        default:
            print("the CBCentralManager state updated without action: \(central.state)")
        }
    }

    func centralManager(
        _: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData _: [String: Any],
        rssi _: NSNumber
    ) {
        self.peripheral = peripheral
        if case .scanning = state {
            print("the CBCentralManager discovered a peripheral:" +
                "\(peripheral.name ?? peripheral.identifier.uuidString)")
            centralManager.connect(peripheral)
            state = .connecting(peripheral)
        }
    }

    func centralManager(_: CBCentralManager, didConnect peripheral: CBPeripheral) {
        if case .connecting = state, let serviceUuid {
            print("the CBCentralManager connected to a peripheral:" +
                " \(peripheral.name ?? peripheral.identifier.uuidString)")
            peripheral.delegate = self
            state = .serviceDiscovery(peripheral)
            peripheral.discoverServices([serviceUuid])
        }
    }

    func centralManager(_: CBCentralManager, didDisconnectPeripheral _: CBPeripheral, error _: (any Error)?) {
        state = .disconnected
    }
}

extension CentralManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: (any Error)?) {
        guard case .serviceDiscovery = state, let serviceUuid else {
            print("discovered a service during an unexpected state: \(state)")
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid.uuidString == serviceUuid.uuidString }),
              error == nil
        else {
            print("failed to discover the expected service:" +
                "\(serviceUuid): \(error?.localizedDescription ?? "unknown error")")
            state = .error
            return
        }
        print("the CBCentralManager discovered a service: \(service.uuid)")
        peripheral.discoverCharacteristics(nil, for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: (any Error)?) {
        guard case .serviceDiscovery = state else {
            print("discovered characteristics during an unexpected state: \(state)")
            return
        }

        if error != nil {
            print("failed to discover the characteristics for the expected service:" +
                "\(serviceUuid?.uuidString ?? "**MISSING**"): \(error!.localizedDescription)")
            state = .error
            return
        }

        if participant.expectedIdent() != nil {
            if let identCharacteristic = service.get(characteristic: CharacteristicUuids.Reader.ident) {
                peripheral.readValue(for: identCharacteristic)
                state = .awaitingIdentUpdate(peripheral, service)
            } else {
                print("failed to find the ident characteristic")
                state = .error
                return
            }
        } else {
            establishConnection(peripheral: peripheral, service: service)
        }
    }

    func peripheral(_: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: (any Error)?) {
        guard error == nil else {
            print("an error occurred when receiving a notification from the Peripheral: \(error!.localizedDescription)")
            return
        }
        guard let data = characteristic.value else {
            print("no data available on the characteristic")
            return
        }
        switch characteristic.uuid {
        case participant.stateCharacteristic():
            handleStateCharacteristicUpdate(data: data)
        case participant.server2ClientCharacteristic():
            handleServer2ClientCharacteristicUpdate(data: data)
        case participant.l2capCharacteristic():
            handleL2CAPCharacteristicUpdate(data: data)
        case CharacteristicUuids.Reader.ident:
            handleIdentCharacteristicUpdate(data: data)
        default:
            print("received a notification on an unknown characteristic: \(characteristic.uuid)")
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: (any Error)?) {
        guard case let .openingL2CAPChannel(peripheral, service) = self.state else {
            print("ignoring L2CAP channel opening during unexpected state: \(self.state)")
            return
        }
        guard let channel = channel, error == nil else {
            print("an error occurred during L2CAP channel establishment:" +
                " \(error?.localizedDescription ?? "unknown error")")
            if case .forceL2CAP = l2capUsage {
                self.state = .error
            } else {
                print("continuing with GATT transmission")
                setupGATTTransmission(peripheral: peripheral, service: service)
            }
            return
        }
        guard let state = service.get(characteristic: participant.stateCharacteristic()) else {
            print("failed to find the state characteristic")
            self.state = .error
            return
        }
        notifyReady(peripheral: peripheral, state: state)
        print("established L2CAP connection")
        self.state = .connectedViaL2CAP(
            peripheral: peripheral,
            state: state,
            l2capManager: L2CAPManager(channel: channel, delegate: participant.transportDelegate())
        )
    }

    func peripheralIsReady(toSendWriteWithoutResponse _: CBPeripheral) {
        if case let .awaitingGATTConnection(gatt) = state {
            print("established GATT connection")
            state = .connectedViaGATT(gatt)
        } else {
            startOrContinueGATTTransmission()
        }
    }
}

/// Operations
private extension CentralManager {
    enum InternalState {
        /// Setting up the client.
        case initializing
        /// Scanning for the peripheral.
        case scanning(
            details: CentralClientDetails,
        )
        /// Establishing a connection with the Peripheral.
        case connecting(CBPeripheral)
        /// Discovering the services of the Peripheral
        case serviceDiscovery(CBPeripheral)
        /// Waiting for the result of reading the Ident characteristic
        case awaitingIdentUpdate(CBPeripheral, CBService)
        /// Waiting for the result of reading the L2CAP characteristic
        case awaitingL2CAPUpdate(CBPeripheral, CBService)
        /// Attempting to open an L2CAP channel, otherwise fallback to GATT.
        case openingL2CAPChannel(CBPeripheral, CBService)
        /// Waiting for the Peripheral to be ready to receive more writes after updating the State characteristic.
        case awaitingGATTConnection(GATTConnection)
        /// Connected to the peripheral via GATT characteristic subscriptions.
        case connectedViaGATT(GATTConnection)
        /// Connected to the peripheral via an L2CAP channel.
        case connectedViaL2CAP(
            peripheral: CBPeripheral,
            state: CBCharacteristic,
            l2capManager: L2CAPManager,
        )
        /// Disconnected from the Peripheral
        case disconnected
        /// An unrecoverable error occurred.
        case error
    }

    class GATTConnection {
        let peripheral: CBPeripheral,
            client2Server: CBCharacteristic,
            state: CBCharacteristic,
            inbox: GATTInbox,
            outbox: GATTOutbox

        init(
            peripheral: CBPeripheral,
            client2Server: CBCharacteristic,
            state: CBCharacteristic,
            inbox: GATTInbox,
            outbox: GATTOutbox
        ) {
            self.peripheral = peripheral
            self.client2Server = client2Server
            self.state = state
            self.inbox = inbox
            self.outbox = outbox
        }
    }

    func establishConnection(peripheral: CBPeripheral, service: CBService) {
        print("establishing the connection")
        if let l2capCharacteristic = service.get(characteristic: participant.l2capCharacteristic()),
           l2capUsage != .disableL2CAP {
            print("reading L2CAP characteristic")
            peripheral.readValue(for: l2capCharacteristic)
            state = .awaitingL2CAPUpdate(peripheral, service)
        } else if case .forceL2CAP = l2capUsage {
            print("L2CAP characteristic missing")
            state = .error
        } else {
            setupGATTTransmission(peripheral: peripheral, service: service)
        }
    }

    func setupGATTTransmission(peripheral: CBPeripheral, service: CBService) {
        print("setting up GATT transmission")
        switch self.state {
        case .serviceDiscovery, .awaitingIdentUpdate, .openingL2CAPChannel, .awaitingL2CAPUpdate:
            break
        default:
            print("ignoring GATT Transmission setup during an invalid state: \(self.state)")
            return
        }

        guard let client2Server = service.get(characteristic: participant.client2ServerCharacteristic()),
              client2Server.properties.contains(.writeWithoutResponse)
        else {
            print("the client2Server characteristic is missing or invalid")
            self.state = .error
            return
        }
        guard let state = service.get(characteristic: participant.stateCharacteristic()) else {
            print("failed to find the state characteristic")
            self.state = .error
            return
        }
        guard let server2Client = service.get(characteristic: participant.server2ClientCharacteristic()) else {
            print("failed to find the server2Client characteristic")
            self.state = .error
            return
        }

        peripheral.setNotifyValue(true, for: server2Client)
        notifyReady(peripheral: peripheral, state: state)

        self.state = .awaitingGATTConnection(GATTConnection(
            peripheral: peripheral,
            client2Server: client2Server,
            state: state,
            inbox: GATTInbox(delegate: participant.transportDelegate()),
            outbox: GATTOutbox(
                mtu: min(peripheral.maximumWriteValueLength(for: .withoutResponse), 515),
                delegate: participant.transportDelegate()
            )
        ))
    }

    func notifyReady(peripheral: CBPeripheral, state: CBCharacteristic) {
        stateCharacteristic = state
        peripheral.setNotifyValue(true, for: state)
        peripheral.writeValue(connectionStateStart, for: state, type: .withoutResponse)
    }

    func handleIdentCharacteristicUpdate(data: Data) {
        guard case let .awaitingIdentUpdate(peripheral, service) =
            state else { return }
        if let expectedIdent = participant.expectedIdent(), data != expectedIdent {
            print("bleIdent does not match")
            print("\(String(describing: data)), \(expectedIdent)")
            state = .error
            return
        }

        establishConnection(peripheral: peripheral, service: service)
    }

    func handleL2CAPCharacteristicUpdate(data: Data) {
        guard case let .awaitingL2CAPUpdate(peripheral, service) =
            state else { return }
        if let psm = data.toUInt16() {
            peripheral.openL2CAPChannel(psm)
            state = .openingL2CAPChannel(peripheral, service)
            return
        } else if case .forceL2CAP = l2capUsage {
            print("L2CAP establishment failed")
            state = .error
        } else {
            setupGATTTransmission(peripheral: peripheral, service: service)
        }
        print("could not read PSM from L2CAP characteristic, continuing with GATT transmission")
    }

    func handleStateCharacteristicUpdate(data: Data) {
        switch data {
        case connectionStateStart:
            print("ignoring 'start' written to state characteristic by the peripheral")
        case connectionStateEnd:
            state = .disconnected
        default:
            print("an unrecognized value was written to state characteristic: \(data)")
        }
    }

    func handleServer2ClientCharacteristicUpdate(data: Data) {
        switch state {
        case let .connectedViaGATT(gatt):
            gatt.inbox.accept(chunk: data)
        default:
            print("received server2Client message during an invalid state: \(state)")
        }
    }

    func startOrContinueGATTTransmission() {
        guard case let .connectedViaGATT(gatt) = state else {
            print("attempted to continue GATT transmission during invalid state: \(state)")
            return
        }
        if let chunk = gatt.outbox.nextChunk() {
            gatt.peripheral.writeValue(chunk.data, for: gatt.client2Server, type: .withoutResponse)
            chunk.commit()
        }
    }
}

private extension CBService {
    func get(characteristic: CBUUID) -> CBCharacteristic? {
        characteristics?.first(where: { $0.uuid == characteristic })
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
            CharacteristicUuids.Reader.state
        case .reader:
            CharacteristicUuids.Mdoc.state
        }
    }

    func server2ClientCharacteristic() -> CBUUID {
        switch self {
        case .mdoc:
            CharacteristicUuids.Reader.server2Client
        case .reader:
            CharacteristicUuids.Mdoc.server2Client
        }
    }

    func client2ServerCharacteristic() -> CBUUID {
        switch self {
        case .mdoc:
            CharacteristicUuids.Reader.client2Server
        case .reader:
            CharacteristicUuids.Mdoc.client2Server
        }
    }

    func l2capCharacteristic() -> CBUUID {
        switch self {
        case .mdoc:
            CharacteristicUuids.Reader.l2cap
        case .reader:
            CharacteristicUuids.Mdoc.l2cap
        }
    }

    func centralDidUpdate(state: CentralManager.State) {
        switch self {
        case let .mdoc(inner):
            inner.centralDidUpdate(state: state)
        case let .reader(inner):
            inner.centralDidUpdate(state: state)
        }
    }

    func expectedIdent() -> Data? {
        switch self {
        case let .mdoc(inner):
            inner.expectedIdent()
        case .reader:
            nil
        }
    }
}
