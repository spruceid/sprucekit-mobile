# NFC for credential presentation

The iOS SDK currently supports the **reader (verifier)** side of an ISO 18013-5
mDL NFC engagement. iOS still does not allow third-party apps to emulate an
NFC tag for general-purpose use, so the **holder (HCE)** side is not available
on iOS.

## Reader (verifier) NFC engagement

For SwiftUI verifier apps, `NfcReaderObservable` is a single entry point that
manages the iOS `NFCTagReaderSession` lifecycle and the APDU exchange. The
`ReaderHandover` it produces is passed to `MdocProximityReader` (via
`init(fromHandover:)`) to start the BLE session — exactly as with QR
engagement.

```swift
struct MyVerifierScreen: View {
    @State private var handover: ReaderHandover?
    @StateObject private var nfc = NfcReaderObservable(
        alertMessage: "Hold near the holder phone"
    )

    var body: some View {
        Group {
            if let handover {
                // Start the BLE session from the handover, e.g.:
                // MDocReaderView(handover: handover, ...)
            } else {
                nfcTabContent
            }
        }
        .onReceive(nfc.$pendingHandover) { newHandover in
            guard let newHandover else { return }
            handover = newHandover
            nfc.consumeHandover()
        }
        .onAppear { nfc.setActive(true) }
        .onDisappear { nfc.setActive(false) }
    }

    @ViewBuilder
    private var nfcTabContent: some View {
        switch nfc.phase {
        case .unsupported:        Text("This device does not support NFC.")
        case .idle:               Button("Start scanning") { nfc.setActive(true) }
        case .waitingForTag:      Text("Tap the holder's phone to share their credential.")
        case .exchanging:         ProgressView()
        case .protocolError(let e): Text(e.localizedDescription)
        }
    }
}
```

Flip `setActive(_:)` off whenever your UI is foreground but not actively
soliciting a tap — e.g. a different tab is showing, or the BLE session is
already running. Each `setActive(true)` opens a fresh `NFCTagReaderSession`;
the SDK auto-re-arms on transient session ends (timeout, tag lost) but **not**
on user-cancel (dismissing the system NFC sheet) — re-arming there would just
loop the modal. Use `.idle` as the cue to show a "tap to scan" retry button.

For non-SwiftUI hosts, the underlying `NfcReaderEngagement` class can be driven
directly via its `Delegate` protocol — see its DocC comments.

### Required app configuration

Reader-mode NFC needs three things in the consuming app target — these are
**not** contributed by the SDK because entitlements and usage descriptions
live on the app target, not on the framework.

1. **Entitlement** — add `com.apple.developer.nfc.readersession.formats` with
   value `["TAG"]` to your app's entitlements file:

   ```xml
   <key>com.apple.developer.nfc.readersession.formats</key>
   <array>
       <string>TAG</string>
   </array>
   ```

2. **Info.plist** — add a usage description and a pre-poll AID list. The
   `select-identifiers` array is **mandatory** for ISO-7816 reading: iOS
   pre-issues `SELECT` for each AID until one is accepted, and only delivers
   the tag if at least one matches. List the **NDEF Type 4 Tag application
   first** because the SDK's APDU handover starts with `SELECT NDEF AID`; the
   mdoc AID is listed as a fallback for non-standard holders.

   ```xml
   <key>NFCReaderUsageDescription</key>
   <string>Used to read mobile driver's license credentials over NFC.</string>
   <key>com.apple.developer.nfc.readersession.iso7816.select-identifiers</key>
   <array>
       <string>D2760000850101</string> <!-- NDEF Type 4 Tag (NFC Forum) -->
       <string>A0000002480400</string> <!-- mDL (ISO 18013-5 §8.3.3.1.2) -->
   </array>
   ```

3. **App ID capability** — at
   [developer.apple.com/account/resources/identifiers/list](https://developer.apple.com/account/resources/identifiers/list),
   open your app's identifier and enable **"NFC Tag Reading"**, then
   regenerate the provisioning profile (the previous one is invalidated by
   adding the capability). This step is mandatory and unrelated to the
   entitlement file — without it the entitlement is inert and sessions fail
   to start. Apple documents the requirement on the
   [`com.apple.developer.nfc.readersession.formats` entitlement page](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.nfc.readersession.formats)
   under "Enabling the capability". No commercial agreement is required for
   reader mode — it's just a checkbox.

### Reader-side caveats

- **Simulator has no NFC.** `NFCTagReaderSession.readingAvailable` returns
  `false` on every simulator; reader-mode flows can only be tested on a real
  iPhone with NFC hardware.
- **Each tap is a fresh session.** Unlike Android's always-armed reader mode,
  iOS tears the session down on every outcome (success, timeout, user cancel,
  error) and shows its own modal scan sheet. The SDK auto-restarts the
  session on transient ends, but a user-cancel surfaces `.idle` and waits
  for an explicit `setActive(true)` to retry.
- **iOS pre-selects an AID.** Whichever AID from `select-identifiers` wins
  becomes the active application before the SDK's APDU loop runs. The
  current handover driver starts with `SELECT NDEF AID` and assumes the
  Type 4 Tag flow, so keep `D2760000850101` in the list and listed first.

## Holder (HCE) NFC engagement

Not supported on iOS at this time. Apple's `com.apple.developer.nfc.hce`
entitlement requires a commercial agreement and is not generally available
for mDL use cases. If your wallet needs holder-side NFC today, target
Android — see the
[Android NFC guide](../../../../../../../android/MobileSdk/src/main/java/com/spruceid/mobile/sdk/nfc/NFC.md).
