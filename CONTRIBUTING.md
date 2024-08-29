# Contributing Documentation

## Setup
The XCode project is generated using `xcodgen`.

## Making Changes Involving the Rust Layer
During development, you can simply depend on your local instance of
`mobile-sdk-rs` by editing the `Package.swift`.

Once everything is complete, you can depend on specific commits from
`mobile-sdk-rs` as Swift's Package Manager enables it, but because of
limitations on the Android's side, you might need to publish a new version of
`mobile-sdk-rs`.

## Release
1. Ensure the dependencies rely on published versions and not commits or
   branches.
2. Ensure `SpruceIDMobileSdk.podspec`'s version is bumped and that the
   dependencies' versions match the versions in `Package.swift`.
3. Push a tag in the format `x.y.z` which should match the version in the
   podspec.
