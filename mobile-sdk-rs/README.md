# Mobile SDK Uniffi Bindings

## Maturity Disclaimer

In its current version, Mobile SDK has not yet undergone a formal security audit
to desired levels of confidence for suitable use in production systems. This
implementation is currently suitable for exploratory work and experimentation
only. We welcome feedback on the usability, architecture, and security of this
implementation and are committed to a conducting a formal audit with a reputable
security firm before the v1.0 release.

## Structure of the Project

```
.               // The Rust crate is at the root
│
├── kotlin      // Contains the Android library project (without the generated
│               // source or dynamic libraries, these are present in the
│               // published package associated with this repo)
│
├── MobileSdkRs // Contains the iOS library, with all the generated source files
│               // and dylibs, as Git is the package manager of Swift
│
├── tests       // Contains cargo tests for Kotlin and Swift for the generated
│               // libraries. These tests act as sanity checks to ensure the
│               // generated libraries will be usable, and are not meant to be
│               // full-fledged functional tests.
```

## Release

Use the [`release` Github Action](https://github.com/spruceid/mobile-sdk-rs/actions/workflows/release.yml)
which is a manually triggered action.

## Pre-requisites

For developing Kotlin code, install `cargo-ndk`.

```bash
cargo install cargo-ndk
```

Ensure you have the following rust build targets installed:

```bash
rustup target install \
    armv7-linux-androideabi \
    aarch64-linux-android \
    i686-linux-android \
    x86_64-linux-android
```

See the `cargo-ndk` [documentation](https://github.com/bbqsrc/cargo-ndk) for more information.

## Build

### Kotlin

```bash
cd kotlin
./gradlew buildCargoNdkDebug
```

If you get this error:
```
> java.io.FileNotFoundException: .../local.properties (No such file or directory)
```

run:
```bash
touch local.properties
```

and try the build again.

### Swift

```bash
cargo swift package -p ios -n MobileSdkRs --release
```
> **⚠** If you need to call `verify_vcb_qrcode_against_mrz` or `verify_pdf417_barcode` in your iOS app, you **must** build with the `--release` flag to avoid runtime errors when executing these methods.

> You will need `cargo-swift` which you can install with `cargo install cargo-swift`.

## Test
In order to run the tests you'll need to [install the kotlin compiler](https://kotlinlang.org/docs/command-line.html) and download a copy of JNA

```
wget https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar
wget https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar
```

JNA will also need to explicitly be on your CLASSPATH.  Simply being in a directory
doesn't necessarily work.  Here is an example of how you might configure this
in your `.bashrc` file

```bash
export CLASSPATH="/path/to/jna-5.14.0.jar:/path/to/kotlinx-coroutines-core-jvm-1.6.4.jar:$CLASSPATH"
```
This lets you just run `cargo test` as normal.


Alternatively, if you don't like the addition to your environment you can
specify it on every invocation of cargo test:

```bash
CLASSPATH="/path/to/jna-5.14.0.jar:/path/to/kotlinx-coroutines-core-jvm-1.6.4.jar" cargo test
```

## Local Development

### Kotlin

To locally test integration with `mobile-sdk-kt`, it is preferrable to use a `mavenLocal()` repository.

To release to `mavenLocal()` you may use the following command:

```bash
cd kotlin/ && VERSION=x.y.z ./gradlew publishDebugPublicationToMavenLocal
```

Where `VERSION` is set to a SemVer (Semantic Versioning). Note that it is possible to use a tagged version, e.g. `0.0.33-SNAPSHOT`, which may be preferrable to denote
that a certain release version is a local release. Other values may be used instead of `SNAPSHOT`.

When adding the local repository to `mobile-sdk-kt`, update the version in the `build.gradle` file:

```kotlin
api("com.spruceid.mobile.sdk.rs:mobilesdkrs:x.y.z-SNAPSHOT")
```

### Swift

To test local intgration with `mobile-sdk-swift`, update the `Package.swift` file target to use a local `path` binary target. For example:

```swift
targets: [
    .binaryTarget(name: "RustFramework", path: "./MobileSdkRs/RustFramework.xcframework"),
    .target(
        name: "SpruceIDMobileSdkRs",
        dependencies: [
            .target(name: "RustFramework")
        ],
        path: "./MobileSdkRs/Sources/MobileSdkRs"
    ),
]
```

> NOTE: For production release, it will be important to replace the binary target with an actual release artifact or revision tag, for example:
> ```
> .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.0.36/RustFramework.xcframework.zip", checksum: "df587ab46f3604df744a9394c229be40e9a65102429ff1b0379dfeb6ca7fdc3c")
> ```


And update the `packages` property in `project.yml` file in `mobile-sdk-swift` to use the local path to `mobile-sdk-rs`, for example:

```yml
packages:
  SpruceIDMobileSdkRs:
    path: "../mobile-sdk-rs"
```

Finally, run `xcodegen` in `mobile-sdk-swift` to generate the `xcodeproject` file to open in xcode.


## Funding

This work is funded in part by the U.S. Department of Homeland Security's Science and Technology Directorate under contract 70RSAT24T00000011 (Open-Source and Privacy-Preserving Digital Credentialing Infrastructure).
Through this contract, SpruceID’s open-source libraries will be used to build privacy-preserving digital credential wallets and verifier capabilities to support standards while ensuring safe usage and interoperability across sectors like finance, healthcare, and various cross-border applications.
To learn more about this work, [read more here](https://spruceid.com/customer-highlight/dhs-highlight) . 
