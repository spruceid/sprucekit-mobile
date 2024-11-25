# Swift Mobile SDK

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

Click [here](./Sources/MobileSdk/ui/SameDeviceOID4VP.md) to see how to configure the same device OpenID4VP flow.

## Contributing

See [CONTRIBUTING.md](/CONTRIBUTING.md).

## Funding

This work is funded in part by the U.S. Department of Homeland Security's Science and Technology Directorate under contract 70RSAT24T00000011 (Open-Source and Privacy-Preserving Digital Credentialing Infrastructure).
Through this contract, SpruceID’s open-source libraries will be used to build privacy-preserving digital credential wallets and verifier capabilities to support standards while ensuring safe usage and interoperability across sectors like finance, healthcare, and various cross-border applications.
To learn more about this work, [read more here](https://spruceid.com/customer-highlight/dhs-highlight) .
