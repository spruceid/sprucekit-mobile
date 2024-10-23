# Kotlin Mobile SDK

## Maturity Disclaimer

In its current version, Mobile SDK has not yet undergone a formal security audit
to desired levels of confidence for suitable use in production systems. This
implementation is currently suitable for exploratory work and experimentation
only. We welcome feedback on the usability, architecture, and security of this
implementation and are committed to a conducting a formal audit with a reputable
security firm before the v1.0 release.

## Architecture

Our Mobile SDKs use shared code, with most of the logic being written once in
Rust, and when not possible, native APIs (e.g. Bluetooth, OS Keychain) are
called in native SDKs.

```
  ┌────────────┐
  │React Native│
  └──────┬─────┘
         │
    ┌────┴────┐
┌───▼──┐   ┌──▼──┐
│Kotlin│   │Swift│
└───┬──┘   └──┬──┘
    └────┬────┘
         │
      ┌──▼─┐
      │Rust│
      └────┘
```
- [Kotlin SDK](https://github.com/spruceid/mobile-sdk-kt)
- [Swift SDK](https://github.com/spruceid/mobile-sdk-swift)
- [Rust layer](https://github.com/spruceid/mobile-sdk-rs)

## Configuring Deep Links for same device flows

Click [here](./MobileSdk/src/main/java/com/spruceid/mobile/sdk/ui/SameDeviceOID4VP.md) to see how to configure the same device OpenID4VP flow.