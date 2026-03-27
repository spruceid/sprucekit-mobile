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
