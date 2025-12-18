# Development Documentation

## Local Development

### Structure of the Project
- `rust/`
    * At the root is the Rust crate which defines the UniFFI bindings
    * `MobileSdkRs/`: generated Swift library
    * `tests/`: contains cargo tests for Kotlin and Swift for the generated libraries. These tests act as sanity checks to ensure the generated libraries will be usable, and are not meant to be full-fledged functional tests.
- `android/`
    * `MobileSdkRs/`: generated Kotlin library
    * `MobileSdk/`: Kotlin SDK built on top of `MobileSdkRs`
    * `Showcase/`: Showcase app, our reference app for this SDK
- `ios/`
    * `MobileSdk/`: Swift SDK built on top of `MobileSdkRS` (under `rust/`)
    * `Showcase/`: Showcase app, our reference app for this SDK
- `flutter/`
    * Flutter plugin providing cross-platform access to the SDK
    * `example/`: Example Flutter app demonstrating the plugin

### General Dependencies

- XCodeGen
    * you can install it on MacOS with `brew install xcodegen`
- Rust targets for Android and iOS:
```bash
rustup target install \
    armv7-linux-androideabi \
    aarch64-linux-android \
    i686-linux-android \
    x86_64-linux-android \
    aarch64-apple-ios-sim \
    aarch64-apple-ios \
    x86_64-apple-ios
```
- `cargo-ndk`
    - `cargo install cargo-ndk` (or use `cargo binstall` for a faster install)
- `cargo-swift`
    - `cargo install --git https://github.com/antoniusnaumann/cargo-swift`

### Android

Everything is in a single Gradle project under `./android/`, including the automatically generated Rust layer.

Make sure to open Android Studio through the terminal to have all your environment working (specifically to have `cargo` in the `PATH`):
```bash
open -na "Android Studio"
```

You explore the Gradle tasks with `./gradlew projects`, `./gradlew tasks` and `./gradlew :<project>:tasks`, but to get you started:
- `./gradlew build` will build everything (if you only build Showcase it will still build the SDK and the Rust layer)
- `./gradlew lint` will lint everything
- `./gradlew test` will test everything

For developing the wallet application, use `./gradlew assembleDebug`. In newer versions of Android Studio you may need to add this as
a 'gradle-aware make' task to the 'Before launch' configuration. 

<details>
<summary>If you get this error: `> java.io.FileNotFoundException: .../local.properties (No such file or directory)`</summary>

run:
```bash
touch android/local.properties
```

</details>

<details>
<summary>If you have issues running the Kotlin uniffi tests in the Rust layer</summary>

In order to run the tests you'll need to [install the kotlin compiler](https://kotlinlang.org/docs/command-line.html) and download a copy of JNA

```
wget https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar
wget https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar
```

JNA will also need to explicitly be on your CLASSPATH.  Simply being in a directory doesn't necessarily work.  Here is an example of how you might configure this in your `.bashrc` file

```bash
export CLASSPATH="/path/to/jna-5.14.0.jar:/path/to/kotlinx-coroutines-core-jvm-1.6.4.jar:$CLASSPATH"
```
This lets you just run `cargo test` as normal.


Alternatively, if you don't like the addition to your environment you can specify it on every invocation of cargo test:

```bash
CLASSPATH="/path/to/jna-5.14.0.jar:/path/to/kotlinx-coroutines-core-jvm-1.6.4.jar" cargo test
```

</details>

### iOS

#### Rust layer

In `./rust/`
```bash
cargo swift package -p ios -n MobileSdkRs
```

#### SDK

(Somewhat optional, if you want/need to use XCode) Run `xcodegen` in `./ios/MobileSdk` to generate the XCode project.

This and the Rust layer are tied together with the `Package.swift` at the root of the repo.

#### Showcase

Run `xcodegen` in `./ios/Showcase` to generate the XCode project.

### Flutter

#### Local development setup

To develop the Flutter plugin against local SDK changes:

1. Build the Rust layer for iOS.

2. Run the example app:
   ```bash
   cd flutter/example
   flutter pub get
   cd ios && pod install && cd ..
   flutter run
   ```

After making changes to Rust code, rebuild and run `pod install` again.

#### Regenerating Pigeon bindings

```bash
cd flutter
dart run pigeon --input pigeons/oid4vci.dart
dart run pigeon --input pigeons/oid4vp.dart
dart run pigeon --input pigeons/oid4vp_mdoc.dart
dart run pigeon --input pigeons/credential_pack.dart
dart run pigeon --input pigeons/mdl_presentation.dart
dart run pigeon --input pigeons/spruce_utils.dart
```

## Releases

### SDKs
Use the [`cd` Github Action](https://github.com/spruceid/sprucekit-mobile/actions/workflows/cd.yml) which is a manually triggered action, and provide the version is the `x.y.z` format.

### Showcase Apps
This is currently done manually and locally.

