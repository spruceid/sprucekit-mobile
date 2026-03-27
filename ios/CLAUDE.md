# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the iOS layer.

## Structure

- **`MobileSdk/`** -- Swift SDK built on `SpruceIDMobileSdkRs` (UniFFI-generated Rust bindings)
  - `Sources/MobileSdk/` -- SDK source
  - `Tests/MobileSdkTests/` -- unit tests
  - `project.yml` -- XCodeGen project definition
  - `.swiftlint.yml` -- SwiftLint config
- **`Showcase/`** -- Reference application
  - `project.yml` -- XCodeGen project definition
  - Targets: `App`, `AppAppKit` (shared logic), `AppUIKit` (UI framework), `AppDcApi` (DC API extension)

## Commands

```bash
# Generate Rust bindings (run from rust/)
cargo swift package -p ios -n MobileSdkRs

# Generate Xcode projects
cd ios/MobileSdk && xcodegen generate
cd ios/Showcase && xcodegen generate

# Build/test SDK (via Xcode or CLI)
xcodebuild -scheme MobileSdk -destination 'platform=iOS Simulator,...'

# Build/test Showcase
xcodebuild -scheme debug -destination 'platform=iOS Simulator,...'

# Lint (strict mode, SDK only)
cd ios/MobileSdk && swiftlint lint --strict
```

## Deployment Targets

SDK targets iOS 14.0, Showcase targets iOS 16.4, and the AppDcApi extension targets iOS 26.0. Be mindful of which APIs are available at each level.

## SDK Capabilities (beyond Rust)

- BLE proximity transport for mDL in-person presentation (`mdoc/proximity/`)
- UI scanners: QR code, MRZ, PDF417 (`ui/`)
- Key management via iOS Keychain / Secure Enclave
- HTTP client implementations (sync + async) for OID4VCI
- App attestation

## XCodeGen

Projects are defined in `project.yml` files and generated with `xcodegen generate`. **Do not edit `.xcodeproj` directly** -- it is regenerated and not committed.

The SDK's `project.yml` references `../../` (the repo root) as the `SpruceIDMobileSdk` Swift package, which ties together the Rust bindings and the Swift SDK via `Package.swift`.

## Showcase App Architecture

The Showcase uses a multi-target structure:
- **App** -- host application (entry point, deep link handling, URL schemes: `openid4vp`, `openid-credential-offer`, `mdoc-openid4vp`, `spruceid`)
- **AppAppKit** -- shared business logic framework (no SDK dependency)
- **AppUIKit** -- UI framework (depends on SpruceIDMobileSdk, SQLite, RiveRuntime)
- **AppDcApi** -- ExtensionKit extension for the Digital Credentials API (`com.apple.identity-document-services.document-provider-ui`). Shares storage and keychain with the host app via app groups.

## SwiftLint

CI runs `swiftlint lint --strict` only on `MobileSdk` -- not on `MobileSdkRs` (auto-generated) and not on `Showcase`.
