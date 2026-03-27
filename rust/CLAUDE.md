# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with the Rust layer.

## Overview

UniFFI library (`mobile-sdk-rs`) that generates Swift and Kotlin bindings for the native SDKs. Crate types: `staticlib`, `lib`, `cdylib`.

## Commands

```bash
# Build
cargo build

# Test (see note below about Kotlin UniFFI tests)
cargo test

# Lint (CI runs with RUSTFLAGS="-Dwarnings")
cargo clippy

# Format check
cargo fmt -- --check

# Generate iOS Swift bindings (from rust/)
cargo swift package -p ios -n MobileSdkRs
# For simulator-only builds:
cargo swift package -p ios -n MobileSdkRs --target aarch64-apple-ios-sim
```

### Kotlin UniFFI Test Setup

`cargo test` includes UniFFI-generated Kotlin tests that require JNA and kotlinx-coroutines on the classpath:

```bash
wget https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar
wget https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar
export CLASSPATH="./jna-5.14.0.jar:./kotlinx-coroutines-core-jvm-1.6.4.jar"
```

## Module Architecture

### Core modules

- **`lib.rs`** -- crate root with UniFFI scaffolding. Re-exports `common::*`, `mdl::*`, `mdl::reader::*`.
- **`common.rs`** -- UniFFI custom type mappings (Key, Value, CredentialType, Uuid, Url, Algorithm, CryptosuiteString). New external types crossing the FFI boundary need `custom_type!` or `custom_newtype!` mappings here.
- **`mdl/`** -- ISO 18013-5 in-person mDL presentation
  - `holder.rs` -- wallet/holder functionality
  - `reader.rs` -- reader/verifier
  - `mcd.rs` -- machine-readable credential data
- **`oid4vp/`** -- OpenID for Verifiable Presentations
  - `dc_api/` -- ISO 18013-7 online presentation via the Digital Credentials API. Annex C (mdoc-specific, does not use OID4VP) and annex D (OpenID4VC HAIP profile, currently mdoc but extensible).
  - `iso_18013_7/` -- ISO 18013-7 annex B (QR code + redirect flow, does not use DC API)
- **`oid4vci/`** -- OpenID for Verifiable Credential Issuance
  - `client/` -- main issuance client (state management, token handling, offer processing)
  - `proof/` -- JWT proof generation
  - `http_client.rs` -- async HTTP client trait (implemented natively on each platform)
- **`credential/`** -- multi-format credential support
  - `format/` -- per-format handlers: CWT, mDoc, JSON-LD VC, JWT VC, IETF SD-JWT, VCDM2 SD-JWT
  - `status.rs` -- credential status list checking
  - `activity_log.rs` -- presentation history

### Supporting modules

- **`verifier/`** -- COSE/P256 verification, outcome types
- **`crypto.rs`** -- key management and crypto operations (KeyStore trait)
- **`did/`** -- DID resolution
- **`storage_manager.rs`** -- persistence abstraction (implemented natively on each platform)
- **`local_store.rs`** -- local credential storage
- **`haci/`** -- High Assurance Credential Issuance (prototype)
- **`trusted_roots/`** -- certificate trust anchors (includes Spruce dev/staging/prod root certs)
- **`vdc_collection.rs`** -- Verifiable Data Collection
- **`presentation/`** -- presentation handling and errors
- **`jwk/`** -- JSON Web Key utilities
- **`jws/`** -- JSON Web Signature handling
- **`cborld.rs`** -- CBOR-LD context support
- **`context.rs`** -- JSON-LD context handling
- **`logger.rs`** -- logging abstraction
- **`w3c_vc_barcodes.rs`** -- W3C VC barcode support

## Key Patterns

### Async at FFI Boundary

Rust uses async/await internally. At the UniFFI boundary, a static tokio runtime handles async:

```rust
static RUNTIME: LazyLock<tokio::runtime::Runtime> = ...;
```

The `block_on()` helper detects whether code is already inside a runtime (e.g. tests) and uses `block_in_place` accordingly. Some functions use `#[uniffi::export(async_runtime = "tokio")]` for direct async export.

### Error Handling

Domain errors use `#[derive(thiserror::Error, uniffi::Error)]` for typed propagation across the FFI boundary. Each module defines its own error enum.

### Foreign Trait Implementations

Traits like `AsyncHttpClient` and `StorageManager` are marked with `#[uniffi::export(with_foreign)]`, allowing native platform code (Swift/Kotlin) to provide implementations.

## External Dependencies

Many core credential/crypto libraries are SpruceID-maintained forks pinned by git rev in `Cargo.toml`. Notable: `josekit` is patched to use RustCrypto instead of OpenSSL (OpenSSL can't be easily used in mobile libraries).

## Testing

Dev dependencies: `rstest` (parameterized tests), `test-log` (trace-enabled logging), `wiremock` (HTTP mocking).

## Generated Code

Do not edit manually:
- `rust/MobileSdkRs/` -- generated Swift package + bindings
- `android/MobileSdkRs/src/main/java/` -- generated Kotlin bindings (managed by Gradle)

