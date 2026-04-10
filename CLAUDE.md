# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Monorepo for verifiable credentials (VC) and mobile driver's license (mDL) SDKs targeting Android, iOS, and Flutter. Most logic is written once in Rust; platform SDKs add native capabilities (BLE, Keychain, UI scanners, Digital Credentials API). Flutter wraps the Kotlin and Swift SDKs via Pigeon platform channels.

See `README.md` for the architecture diagram, usage instructions, and deep link/NFC configuration. See `CONTRIBUTING.md` for local development setup and dependency installation.

## Layer-Specific Guides

Each layer has its own CLAUDE.md with build commands and architecture details:

- [Rust core library](rust/CLAUDE.md)
- [iOS SDK + Showcase](ios/CLAUDE.md)
- [Android SDK + Showcase](android/CLAUDE.md)
- [Flutter plugin](flutter/CLAUDE.md)

## Cross-Layer Development Workflow

Changes to the Rust layer require regenerating bindings before platform work:

- **iOS**: `cd rust && cargo swift package -p ios -n MobileSdkRs`
  - CI enforces the generated `rust/MobileSdkRs/Sources/MobileSdkRs/mobile_sdk_rs.swift` is committed via `git diff --exit-code`
- **Android**: Bindings regenerated automatically by the Gradle cargo-ndk plugin during build
- **Flutter**: Wraps native SDKs (not Rust directly). Rebuild the relevant native SDK, then `pod install` (iOS) or Gradle rebuild (Android).

## CI

CI runs on push to main and PRs with four jobs (rust, ios, android, flutter). See `.github/workflows/ci.yml`.

`RUSTFLAGS="-Dwarnings"` is set globally -- all Rust warnings are errors in CI.

## Dependency License Checks

All layers enforce that dependency licenses are in an allowlist of permissive licenses. The canonical allowlist is in `rust/deny.toml` under `[licenses] allow`. It is duplicated in:

- **Rust**: `rust/deny.toml` -- `cargo deny check licenses`
- **Android**: `android/build.gradle.kts` -- `cashapp/licensee` Gradle plugin (`./gradlew licensee`)
- **iOS**: `ios/check-swift-licenses.py` -- custom script checking SPM `Package.resolved` + CocoaPods podspec consistency
- **Flutter**: CI workflow flags -- `very_good packages check licenses --allowed="..."`

When changing the allowlist, update all four locations.

When adding a new iOS/Swift dependency, update `ios/check-swift-licenses.py` with the package name, license, and (if applicable) the CocoaPods-to-SPM name mapping.

## Versioning

All SDKs are versioned together. Release process is in `CONTRIBUTING.md`.

## Package.swift

`Package.swift` at the repo root serves dual purposes: local development (binary target uses a local path) and release distribution (CD workflow rewrites to a remote GitHub release URL with checksum, then reverts after tagging). Don't be surprised if it looks different between commits on main.

