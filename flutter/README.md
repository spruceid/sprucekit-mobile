# sprucekit_mobile

Flutter plugin for the SpruceKit Mobile SDKs. It bridges the iOS and Android
SDKs over Pigeon platform channels. See the [example app](./example) for
every API in use, and [CLAUDE.md](./CLAUDE.md) for the layout and the
generation commands.

## NFC tap-to-share (Android)

`MdlPresentation.initializeNfcPresentation` presents an mDL after a reader
tap, with ISO 18013-5 static handover and the data exchange over BLE. To make
this work in every consumer app, the plugin manifest declares:

- A `HostApduService`, `com.spruceid.sprucekit_mobile.NfcPresentationService`,
  on the mdoc AID `A0000002480400`. It answers a reader only while the app is
  in the foreground and a tap is armed.
- `android.permission.NFC`, and `uses-feature` entries for `android.hardware.nfc`
  and `android.hardware.nfc.hce` with `required="false"`, so the app still
  installs on phones without NFC.

Manifest merge brings all of this into your app. You add no Android code.

### If your app already ships its own mdoc HCE service

Two services on the same AID make Android show a chooser on every tap. Keep
one. To drop the plugin's service, add this under `<application>` in your
manifest, with `xmlns:tools="http://schemas.android.com/tools"` on the root:

```xml
<service
    android:name="com.spruceid.sprucekit_mobile.NfcPresentationService"
    tools:node="remove" />
```

The NFC methods then return an error, and QR engagement is unaffected.

### Service description strings

Android shows the service description in the AID chooser. The plugin ships
English defaults. Override them in your app's `res/values-xx/strings.xml`:

```xml
<string name="sprucekit_nfc_hce_service_desc">Share a mobile document with a tap</string>
<string name="sprucekit_nfc_hce_group_desc">ISO 18013-5 mobile document</string>
```

### Reading the state

The NFC detail rides on the existing `MdlPresentationState` values, so a
`switch` over the enum keeps compiling. Read it through the getters on
`MdlPresentationStateUpdate`:

| Getter | Main state | Meaning |
|---|---|---|
| `isWaitingForNfcTap` | `initializing` | The phone answers reader taps. Show the tap screen. |
| `isConnectingViaNfc` | `initializing` | The tap delivered the BLE carrier. Connection in progress. |
| `isNfcUnavailable` | `error` | NFC was turned off while waiting. Fall back to QR. |

`isNfcPresentationAvailable` is true on Android when NFC is on and the phone
supports host card emulation. It is always false on iOS, and
`initializeNfcPresentation` returns an error there.
