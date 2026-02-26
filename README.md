# SpruceKit Mobile

SpruceKit Mobile is a collection of libraries and examples for integrating verifiable credentials (VC) and mobile driver's licenses (mDL) into Android and iOS applications.

## Maturity Disclaimer

In its current version, SpruceKit Mobile has not yet undergone a formal security audit to desired levels of confidence for suitable use in production systems. This implementation is currently suitable for exploratory work and experimentation only. We welcome feedback on the usability, architecture, and security of this implementation and are committed to a conducting a formal audit with a reputable security firm before the v1.0 release.

## Building

### iOS

Requirements:
- Xcode 16.4
- Rust 2021 / Cargo 1.91.1
- [cargo-swift](https://github.com/antoniusnaumann/cargo-swift/) 0.30
  - Install via `cargo install cargo-swift@0.30 -f`
  - **NB**: cargo-swift version must match project UniFFI version!
- [xcodegen](https://github.com/yonaskolb/XcodeGen) 2.44.1
  - Install via `brew install xcodegen` assuming Homebrew is installed, otherwise follow xcodegen instructions.

Steps:

1. Build Rust to Swift Package

```sh
cd rust
cargo swift package -p ios -n MobileSdkRs
cd ..
```

2. Generate Xcode project

```sh
cd ios/MobileSdk
xcodegen generate
cd ../Showcase
xcodegen generate
cd ../..
```

3. Open project with Xcode, or build on command line.

```sh
cd ios/Showcase
xcodebuild -scheme debug -destination "platform=iOS Simulator,name=iPhone 17" build
```

**NB:** Apple really wants you to open the XCode IDE. Many problems (such as signing keys) are solved just by opening the IDE, building once, and then closing it.

## Usage

### iOS

Import `https://github.com/spruceid/sprucekit-mobile` and use the product `SpruceIDMobileSdk`.

### Android

See https://central.sonatype.com/artifact/com.spruceid.mobile.sdk/mobilesdk.

## Architecture

Our Mobile SDKs use shared code, with most of the logic being written once in Rust, and when not possible, native APIs (e.g. Bluetooth, OS Keychain) are called in native SDKs.

```
┌────────┐ ┌────────┐
│Showcase│ │Showcase│
│Android │ │  iOS   │
└────┬───┘ └───┬────┘
     │         │
     │         │
 ┌───▼──┐   ┌──▼──┐
 │Kotlin│   │Swift│
 └───┬──┘   └──┬──┘
     └────┬────┘
          │
       ┌──▼─┐
       │Rust│
       └────┘
```
- [Rust layer](./rust)
- [Kotlin SDK](./android)
- [Swift SDK](./ios)
- [Showcase Android](./android/Showcase)
- [Showcase iOS](./ios/Showcase)

## Configuring Deep Links for same device flows

To configure the same device OpenID4VP flow:
- Android: [See here](./android/MobileSdk/src/main/java/com/spruceid/mobile/sdk/ui/SameDeviceOID4VP.md)
- iOS: [See here](./ios/MobileSdk/Sources/MobileSdk/ui/SameDeviceOID4VP.md)

## Configuring NFC for credential presentation

To configure NFC presentation support:
- Android: [See here](./android/MobileSdk/src/main/java/com/spruceid/mobile/sdk/nfc/NFC.md)
- iOS: iOS does not yet allow third-party applications to emulate NFC tags. This may be available in an upcoming release.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).

## Funding

This work is funded in part by the U.S. Department of Homeland Security's Science and Technology Directorate under contract 70RSAT24T00000011 (Open-Source and Privacy-Preserving Digital Credentialing Infrastructure).
Through this contract, SpruceID’s open-source libraries will be used to build privacy-preserving digital credential wallets and verifier capabilities to support standards while ensuring safe usage and interoperability across sectors like finance, healthcare, and various cross-border applications.
To learn more about this work, [read more here](https://spruceid.com/customer-highlight/dhs-highlight) .
