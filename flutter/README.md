# sprucekit_mobile

Flutter plugin for the SpruceKit Mobile SDKs. It bridges the iOS and Android
SDKs over Pigeon platform channels. See the [example app](./example) for
every API in use, and [CLAUDE.md](./CLAUDE.md) for the layout and the
generation commands.

## NFC tap-to-share (Android)

`MdlPresentation.initializeNfcPresentation` presents an mDL after a reader
tap, with ISO 18013-5 static handover and the data exchange over BLE. The
feature is opt-in. The plugin ships the pieces, and your app declares the
service:

- The plugin manifest adds `android.permission.NFC` and `uses-feature`
  entries for `android.hardware.nfc` and `android.hardware.nfc.hce` with
  `required="false"`, so the app still installs on phones without NFC.
- The plugin ships the `HostApduService` class,
  `com.spruceid.sprucekit_mobile.NfcPresentationService`, and the AID
  resource `@xml/sprucekit_nfc_hce_service` for the mdoc AID `A0000002480400`.
- Your app declares the service. Without it, `isNfcPresentationAvailable`
  is false, `initializeNfcPresentation` returns an error that says so, and QR
  engagement works as before.

### Enable NFC tap-to-share in your app

Add this under `<application>` in `android/app/src/main/AndroidManifest.xml`.
The [example app](./example/android/app/src/main/AndroidManifest.xml) has it.

```xml
<service
    android:name="com.spruceid.sprucekit_mobile.NfcPresentationService"
    android:exported="true"
    android:permission="android.permission.BIND_NFC_SERVICE">
    <intent-filter>
        <action android:name="android.nfc.cardemulation.action.HOST_APDU_SERVICE" />
    </intent-filter>
    <meta-data
        android:name="android.nfc.cardemulation.host_apdu_service"
        android:resource="@xml/sprucekit_nfc_hce_service" />
</service>
```

The service runs the handover only while the app is in the foreground and a
tap is armed. At any other time it answers a reader with "file not found", so
the reader does not wait on the phone. The AID resource sets
`requireDeviceUnlock="true"`, so a locked phone stays silent.

If your app already ships its own mdoc HCE service, keep one. Two services on
the same AID make Android show a chooser on every tap.

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

`getNfcAvailability` tells the wallet whether a tap can start, and the reason
when it cannot:

| `MdlNfcAvailability` | Meaning | What the wallet shows |
|---|---|---|
| `available` | NFC is on and a tap can be armed. | The tap screen. |
| `turnedOff` | The phone supports it, but NFC is off. | A hint to turn NFC on, with the system settings. |
| `unsupportedPlatform` | iOS. | No tap option. |
| `noAdapter` | The phone has no NFC. | No tap option. |
| `noHostCardEmulation` | The phone has NFC but cannot emulate a card. | No tap option. |
| `serviceNotDeclared` | The app manifest does not declare the service. | No tap option. Fix the manifest. |

`isSupported` on the value is true for `available` and `turnedOff`, which are
the two cases where a tap option makes sense. `isNfcPresentationAvailable` is
the same as `available`. `initializeNfcPresentation` returns an error with the
reason in words when the value is not `available`.
A phone that leaves the reader before the handover finishes stays armed, so
the user taps again without a restart.
