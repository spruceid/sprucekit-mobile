import CoreBluetooth
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

/// Single-device wiring self-test for NFC static-handover holder presentation.
///
/// Replaces the NFC radio with an in-memory APDU loopback (holder `ApduHandoverDriver` ↔
/// reader `ReaderApduHandoverDriver`), then brings up both BLE roles on one device. A phone
/// can't BLE-connect to itself, so this verifies wiring only — no full exchange, no entitlement.
public struct NfcHolderSelfTestView: View {
    @StateObject private var runner: NfcHolderSelfTestRunner

    public init(mdoc: Mdoc) {
        _runner = StateObject(wrappedValue: NfcHolderSelfTestRunner(mdoc: mdoc))
    }

    public var body: some View {
        VStack(spacing: 16) {
            Text("NFC Holder Self-Test")
                .font(.headline)
            Text("Single-device wiring check — no entitlement needed: a software loopback stands in for the NFC tap, then confirms the holder engages from the handover and brings up its BLE session. It doesn't run the real data exchange (one phone can't BLE-connect to itself), and a real over-the-air NFC tap additionally needs the Apple SE entitlement (pending).")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            HStack(alignment: .top, spacing: 24) {
                stateColumn(title: "Holder", value: runner.holderState)
                stateColumn(title: "Reader", value: runner.readerState)
            }

            if let outcome = runner.outcome {
                Text(outcome)
                    .font(.subheadline.bold())
                    .foregroundStyle(runner.succeeded ? .green : .red)
                    .multilineTextAlignment(.center)
            }

            ScrollView {
                Text(runner.log)
                    .font(.system(.caption2, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: 240)
            .padding(8)
            .background(Color(.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 8))

            Button(runner.isRunning ? "Running…" : "Run self-test") {
                runner.run()
            }
            .buttonStyle(.borderedProminent)
            .disabled(runner.isRunning)
        }
        .padding()
        .onDisappear { runner.stop() }
    }

    private func stateColumn(title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline.bold())
            Text(value).font(.system(.caption, design: .monospaced))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

final class NfcHolderSelfTestRunner: ObservableObject {
    @Published var holderState = "—"
    @Published var readerState = "—"
    @Published var log = ""
    @Published var outcome: String?
    @Published var isRunning = false
    @Published var succeeded = false

    private let mdoc: Mdoc
    private var holder: MdocProximityPresentationManager?
    private var reader: MdocProximityReader?
    private var holderDelegate: HolderDelegate?
    private var readerDelegate: ReaderDelegate?
    private var finished = false
    private var holderEngaged = false
    private var readerAdvertising = false

    init(mdoc: Mdoc) {
        self.mdoc = mdoc
    }

    func run() {
        guard !isRunning else { return }
        isRunning = true
        finished = false
        succeeded = false
        outcome = nil
        log = ""
        holderState = "starting"
        readerState = "starting"

        do {
            append("Running APDU handover loopback (no NFC radio)…")
            let (readerHandover, carrier) = try Self.loopbackHandover()
            append("Handover negotiated — BLE UUID \(carrier.getUuid())")

            let holderDelegate = HolderDelegate(runner: self)
            self.holderDelegate = holderDelegate
            holder = MdocProximityPresentationManager(
                mdoc: mdoc,
                delegate: holderDelegate,
                engagement: .NFC(carrier)
            )

            let readerDelegate = ReaderDelegate(runner: self)
            self.readerDelegate = readerDelegate
            reader = MdocProximityReader(
                fromHandover: readerHandover,
                delegate: readerDelegate,
                requestedItems: ["org.iso.18013.5.1": ["given_name": false, "family_name": false]],
                trustAnchorRegistry: TrustedCertificatesDataStore.shared.getAllCertificates().map { $0.content },
                l2capUsage: .disableL2CAP
            )
            append("Holder scanning + reader advertising over BLE…")

            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
                self?.fail("Holder/reader did not reach ready states in time — check Bluetooth permission")
            }
        } catch {
            fail("Loopback failed: \(error)")
        }
    }

    func stop() {
        holder?.disconnect()
        reader?.disconnect()
        holder = nil
        reader = nil
        holderDelegate = nil
        readerDelegate = nil
    }

    /// Drive the reader↔holder APDU drivers in memory (standing in for the NFC radio).
    static func loopbackHandover() throws -> (ReaderHandover, NegotiatedCarrierInfo) {
        let holderDriver = try ApduHandoverDriver(negotiated: false, strict: false)
        try holderDriver.regenerateStaticBleKeys()
        let readerInit = newReaderApduHandoverDriver()
        var command = readerInit.initialApdu
        for _ in 0..<64 {
            let response = holderDriver.processApdu(command: command)
            switch try readerInit.driver.processRapdu(command: response) {
            case .inProgress(let next):
                command = next
            case .done(let handover):
                guard let carrier = holderDriver.getCarrierInfo() else {
                    throw SelfTestError.noCarrierInfo
                }
                return (handover, carrier)
            }
        }
        throw SelfTestError.handoverDidNotComplete
    }

    fileprivate func holderStateChanged(_ state: MdocProximityPresentationManager.State) {
        holderState = Self.describe(holder: state)
        append("holder → \(holderState)")
        switch state {
        case .connectingViaNfc:
            holderEngaged = true
            checkWiring()
        case let .receivedRequest(request):
            // Only reached if a reader actually connected (two-device run): auto-approve.
            let items = Self.approveAll(request)
            append("holder auto-approving: \(items)")
            request.approve(items: items)
        case .sentResponse:
            succeed("Full session completed — holder presented the credential over BLE")
        case .error, .readerDisconnected:
            fail("Holder ended in \(holderState)")
        default:
            break
        }
    }

    fileprivate func readerStateChanged(_ state: MdocProximityReader.State) {
        readerState = Self.describe(reader: state)
        append("reader → \(readerState)")
        switch state {
        case .connecting:
            readerAdvertising = true
            checkWiring()
        case .receivedResponse:
            succeed("Full session completed — reader received & verified the response over BLE")
        case .error, .mdocDisconnected:
            fail("Reader ended in \(readerState)")
        default:
            break
        }
    }

    /// Single phone can't BLE-connect to itself → success = both sides reached their ready states (wiring OK).
    private func checkWiring() {
        guard holderEngaged, readerAdvertising, !finished else { return }
        succeed("Wiring verified — holder engaged from the NFC handover (BLE central up); reader accepted the handover (advertising).")
    }

    private func succeed(_ message: String) {
        guard !finished else { return }
        finished = true
        isRunning = false
        succeeded = true
        outcome = "✅ \(message)"
        append("✅ \(message)")
        stop()
    }

    private func fail(_ message: String) {
        guard !finished else { return }
        finished = true
        isRunning = false
        succeeded = false
        outcome = "❌ \(message)"
        append("❌ \(message)")
        stop()
    }

    private func append(_ line: String) {
        log += (log.isEmpty ? "" : "\n") + line
    }

    private static func approveAll(
        _ request: MdocProximityPresentationManager.Request
    ) -> [String: [String: [String]]] {
        var approved: [String: [String: [String]]] = [:]
        for itemRequest in request.items {
            var namespaces: [String: [String]] = [:]
            for (namespace, items) in itemRequest.namespaces {
                namespaces[namespace] = Array(items.keys)
            }
            approved[itemRequest.docType] = namespaces
        }
        return approved
    }

    private static func describe(holder state: MdocProximityPresentationManager.State) -> String {
        switch state {
        case .initializing: return "initializing"
        case .action(let required): return "action(\(required))"
        case .connecting: return "connecting(qr)"
        case .connectingViaNfc: return "connectingViaNfc"
        case .connected: return "connected"
        case .receivingRequest: return "receivingRequest"
        case .receivedRequest: return "receivedRequest"
        case .sendingResponse: return "sendingResponse"
        case .sentResponse: return "sentResponse"
        case .requestDismissed: return "requestDismissed"
        case .readerDisconnected: return "readerDisconnected"
        case .error: return "error"
        }
    }

    private static func describe(reader state: MdocProximityReader.State) -> String {
        switch state {
        case .initializing: return "initializing"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .sendingRequest: return "sendingRequest"
        case .sentRequest: return "sentRequest"
        case .receivingResponse: return "receivingResponse"
        case .receivedResponse: return "receivedResponse"
        case .mdocDisconnected: return "mdocDisconnected"
        case .error: return "error"
        case .action(let required): return "action(\(required))"
        }
    }

    enum SelfTestError: Error { case noCarrierInfo, handoverDidNotComplete }
}

private final class HolderDelegate: MdocProximityPresentationManager.Delegate {
    private weak var runner: NfcHolderSelfTestRunner?
    init(runner: NfcHolderSelfTestRunner) { self.runner = runner }
    func connectionState(changedTo state: MdocProximityPresentationManager.State) {
        DispatchQueue.main.async { [weak runner] in runner?.holderStateChanged(state) }
    }
}

private final class ReaderDelegate: MdocProximityReader.Delegate {
    private weak var runner: NfcHolderSelfTestRunner?
    init(runner: NfcHolderSelfTestRunner) { self.runner = runner }
    func connectionState(changedTo state: MdocProximityReader.State) {
        DispatchQueue.main.async { [weak runner] in runner?.readerStateChanged(state) }
    }
}
