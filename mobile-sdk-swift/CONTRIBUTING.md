# Contributing Documentation

## Setup
The XCode project is generated using `xcodgen`.

## Making Changes Involving the Rust Layer
During development, you can simply depend on your local instance of
`mobile-sdk-rs` by editing the `Package.swift`.

Once everything is complete, `mobile-sdk-rs` will need to be published as it
needs to have the dynamic libraries published.

## Checking CocoaPods linter locally
The release action also releases the package on CocoaPods, and this release
has a CocoaPod code verification. You can check your changes locally by 
running `pod lib lint SpruceIDMobileSdk.podspec`

## Release
1. Ensure the dependencies rely on published versions and not commits or
   branches.
2. Ensure `SpruceIDMobileSdk.podspec`'s version is bumped and that the
   dependencies' versions match the versions in `Package.swift`.
3. Push a tag in the format `x.y.z` which should match the version in the
   podspec.
