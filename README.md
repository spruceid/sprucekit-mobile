# SpruceKit Mobile

SpruceKit Mobile is a collection of libraries and examples for integrating verifiable credentials (VC) and mobile driver's licenses (mDL) into Android, iOS, and Flutter applications.

## Maturity Disclaimer

In its current version, SpruceKit Mobile has not yet undergone a formal security audit to desired levels of confidence for suitable use in production systems. This implementation is currently suitable for exploratory work and experimentation only. We welcome feedback on the usability, architecture, and security of this implementation and are committed to a conducting a formal audit with a reputable security firm before the v1.0 release.

## Usage

### iOS

Import `https://github.com/spruceid/sprucekit-mobile` and use the product `SpruceIDMobileSdk`.

### Android

See https://central.sonatype.com/artifact/com.spruceid.mobile.sdk/mobilesdk.

### Flutter

Add `sprucekit_mobile` to your `pubspec.yaml` dependencies. See the [Flutter plugin](./flutter) for details.

### HTTP client and proxies

The Rust layer sends its HTTP requests through the native HTTP client of the platform. The requests then obey the proxy settings and the trust store of the device. Register the client once when the app starts:

- iOS: call `RustHttpClient.configure()` from `SpruceIDMobileSdk`.
- Android: call `RustHttpClient.configure()` from `com.spruceid.mobile.sdk`.
- Flutter: the plugin registers the client when it attaches to the engine.

Pass your own `AsyncHttpClient` to `configure` to control the transport. Without the call, the Rust layer uses a built-in client that does not read the device proxy settings. Requests made by `did:web` resolution and by the VCALM crate use their own HTTP client and do not obey the registration yet.

## Architecture

Our Mobile SDKs use shared code, with most of the logic being written once in Rust, and when not possible, native APIs (e.g. Bluetooth, OS Keychain) are called in native SDKs.

```
               ┌───────┐
               │Flutter│
               │plugin │
               └─┬────┬┘
                 │    │
┌────────┐  ┌────▼─┐ ┌▼────┐  ┌────────┐
│Showcase├──▶Kotlin│ │Swift◀──┤Showcase│
│Android │  └──┬───┘ └──┬──┘  │  iOS   │
└────────┘     └────┬───┘     └────────┘
                 ┌──▼─┐
                 │Rust│
                 └────┘
```
- [Rust layer](./rust)
- [Kotlin SDK](./android)
- [Swift SDK](./ios)
- [Flutter plugin](./flutter)
- [Showcase Android](./android/Showcase)
- [Showcase iOS](./ios/Showcase)
- [Flutter example app](./flutter/example)

## Configuring Deep Links for same device flows

To configure the same device OpenID4VP flow:
- Android: [See here](./android/MobileSdk/src/main/java/com/spruceid/mobile/sdk/ui/SameDeviceOID4VP.md)
- iOS: [See here](./ios/MobileSdk/Sources/MobileSdk/ui/SameDeviceOID4VP.md)

## Configuring NFC for credential presentation

To configure NFC presentation support:
- Android: [See here](./android/MobileSdk/src/main/java/com/spruceid/mobile/sdk/nfc/NFC.md) (reader + holder/HCE)
- iOS: [See here](./ios/MobileSdk/Sources/MobileSdk/nfc/NFC.md) (reader only — iOS does not yet allow third-party HCE)

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).

## Funding

This work is funded in part by the U.S. Department of Homeland Security's Science and Technology Directorate under contract 70RSAT24T00000011 (Open-Source and Privacy-Preserving Digital Credentialing Infrastructure).
Through this contract, SpruceID’s open-source libraries will be used to build privacy-preserving digital credential wallets and verifier capabilities to support standards while ensuring safe usage and interoperability across sectors like finance, healthcare, and various cross-border applications.
To learn more about this work, [read more here](https://spruceid.com/customer-highlight/dhs-highlight) .
