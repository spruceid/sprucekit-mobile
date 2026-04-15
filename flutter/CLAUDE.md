# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the Flutter layer.

## Overview

Flutter plugin (`sprucekit_mobile`) wrapping the iOS and Android SpruceKit Mobile SDKs via [Pigeon](https://pub.dev/packages/pigeon) platform channels. Does **not** call Rust directly -- it bridges to the native SDKs which in turn call Rust.

## Structure

- **`lib/sprucekit_mobile.dart`** -- library entry point, exports all APIs
- **`lib/pigeon/`** -- generated Dart code (do not edit, regenerate from `pigeons/`)
- **`lib/src/scanner.dart`** -- native Platform View scanner widget (QR, MRZ, PDF417)
- **`pigeons/`** -- Pigeon API definitions (source of truth for cross-platform APIs)
- **`ios/Classes/`** -- Swift host API implementations + generated Pigeon code
- **`android/src/main/kotlin/`** -- Kotlin host API implementations + generated Pigeon code
- **`example/`** -- Example app demonstrating all plugin capabilities

## Commands

```bash
# Install dependencies
flutter pub get

# Analyze
flutter analyze

# Format check
dart format --set-exit-if-changed lib/sprucekit_mobile.dart pigeons/ example/

# Regenerate Pigeon bindings (run for each API after modifying pigeons/)
dart run pigeon --input pigeons/oid4vci.dart
dart run pigeon --input pigeons/oid4vp.dart
dart run pigeon --input pigeons/oid4vp_mdoc.dart
dart run pigeon --input pigeons/credential_pack.dart
dart run pigeon --input pigeons/mdl_presentation.dart
dart run pigeon --input pigeons/spruce_utils.dart
dart run pigeon --input pigeons/dc_api.dart
```

## Key Patterns

- The **Scanner** widget is a native Platform View (not Pigeon-based), communicating via `MethodChannel` per instance
- All cross-platform API changes start in `pigeons/` source files, then regenerate with `dart run pigeon`
- Generated code lands in three places: `lib/pigeon/` (Dart), `ios/Classes/` (Swift `.g.swift`), `android/src/main/kotlin/` (Kotlin `.g.kt`)
- Native adapter classes (e.g., `Oid4vciAdapter.swift`, `Oid4vciAdapter.kt`) implement the generated Pigeon host APIs by delegating to the native SDKs
- For releases, the plugin depends on **published** native SDK versions (Maven Central for Android, CocoaPods for iOS). For local dev, it uses source builds.
- **Adding a new Rust function to the Android adapter**: the Flutter plugin references the *published* `mobilesdkrs` artifact, so a newly-added Rust function won't be available until the next SDK release. To build the Flutter example locally against your local SDK:
  1. Publish the local SDK to Maven Local:
     ```bash
     cd android && touch local.properties
     VERSION=0.0.1-local ./gradlew :MobileSdkRs:publishDebugPublicationToMavenLocal
     ```
     > **Note:** Use `publishDebugPublicationToMavenLocal` (not `publishToMavenLocal`) to avoid GPG signing errors. The `release` publication requires GPG signing (`gpg` command + keys), which is only needed for Maven Central. The `debug` publication skips signing and works for local development.
  2. In `flutter/android/build.gradle`, make **three** changes:
     - Uncomment `mavenLocal()` in the `repositories` block.
     - Add `exclude` to the `mobilesdk` dependency to prevent its transitive dependency on the published `mobilesdkrs` from overriding your local version.
     - Change the `mobilesdkrs` version to your local version.
     ```groovy
     // Before (committed state):
     implementation("com.spruceid.mobile.sdk:mobilesdk:0.14.14")
     implementation("com.spruceid.mobile.sdk.rs:mobilesdkrs:0.14.14")

     // After (local testing):
     implementation("com.spruceid.mobile.sdk:mobilesdk:0.14.14") {
         exclude group: "com.spruceid.mobile.sdk.rs", module: "mobilesdkrs"
     }
     implementation("com.spruceid.mobile.sdk.rs:mobilesdkrs:0.0.1-local")
     ```
     > **Why the exclude?** `mobilesdk:0.14.14` declares a transitive dependency on `mobilesdkrs:0.14.14` with a `strictly` constraint. Without the exclude, Gradle resolves your `0.0.1-local` back to `0.14.14`, silently ignoring your local build.
  3. Build the example: `cd flutter/example && flutter build apk`
  4. **Before pushing**, revert all three changes in `build.gradle` (re-comment `mavenLocal()`, remove `exclude`, restore version to `0.14.14`).

  The Flutter CI only runs Dart analysis (`flutter analyze`) — Kotlin compilation is not checked in CI, so the PR will pass even before the SDK is published.

- **Adding a new Rust function to the iOS adapter**: The Flutter example iOS app uses local pods (`:path => '../../../'` in `flutter/example/ios/Podfile`), so it picks up local Rust and Swift SDK changes automatically — no publishing step needed. However, you must rebuild the xcframework:
  1. Clean and rebuild the Rust iOS framework + Swift bindings:
     ```bash
     cd rust
     cargo clean                                    # Prevent stale build cache
     cargo swift package -p ios -n MobileSdkRs      # Rebuild xcframework + Swift bindings
     ```
     > **Important:** Always run `cargo clean` before `cargo swift package` if you've pulled new commits or switched branches. Without it, the xcframework may contain stale object files that don't match the regenerated Swift bindings, causing "Cannot find type/symbol" errors at build time.
  2. Re-install pods and build:
     ```bash
     cd flutter/example/ios
     pod install
     cd ..
     fvm flutter build ios --debug --no-codesign
     ```
  3. No changes to commit — the Podfile is already configured for local development, and `RustFramework.xcframework` is in `.gitignore`.

  > **Tip:** If you see Swift errors like "Cannot find type 'UniffiVTableCallback...'" or "Cannot find 'uniffi_mobile_sdk_rs_checksum_...'", it almost always means the xcframework is stale. Run `cargo clean && cargo swift package -p ios -n MobileSdkRs` to fix it.
