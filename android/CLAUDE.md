# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the Android layer.

## Structure

Single Gradle project with three modules:

- **`MobileSdkRs/`** -- Auto-generated Kotlin bindings from Rust via cargo-ndk + UniFFI. **Do not edit manually.** Rebuilt automatically when Rust source changes.
- **`MobileSdk/`** -- Native Android SDK built on MobileSdkRs
  - `ble/` -- BLE transport for mDL proximity presentation (extensive: GATT, L2CAP, state machine, retry, error classification)
  - `ui/` -- Jetpack Compose UI components (QR/MRZ/PDF417 scanners, card views)
  - `dcapi/` -- Digital Credentials API integration (androidx.credentials)
  - Core: `CredentialPack.kt`, `OID4VCI.kt`, `KeyManager.kt`, `StorageManager.kt`
- **`Showcase/`** -- Reference app (Hilt DI, Room database, Ktor HTTP)

## Commands

**Important**: Run tests and lint per-module individually to avoid race conditions (MobileSdk may not wait for MobileSdkRs to finish compiling when run together).

```bash
# Build everything
./gradlew build

# Test (per-module)
./gradlew :mobilesdkrs:test -Prust-target=arm64
./gradlew :mobilesdk:test -Prust-target=arm64
./gradlew :showcase:test -Prust-target=arm64

# Lint (per-module)
./gradlew :mobilesdkrs:lint -Prust-target=arm64
./gradlew :mobilesdk:lint -Prust-target=arm64
./gradlew :showcase:lint -Prust-target=arm64

# Debug build
./gradlew assembleDebug

# Explore Gradle tasks
./gradlew projects
./gradlew tasks
./gradlew :<project>:tasks
```

## Gotchas

- **Android Studio must be opened from the terminal** to have `cargo` in PATH: `open -na "Android Studio"`
- If you get `FileNotFoundException: .../local.properties`: run `touch android/local.properties`
