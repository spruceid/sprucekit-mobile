// swift-tools-version: 5.9

import Foundation
import PackageDescription

// SPRUCEKIT_SDK_FROM_SOURCE=1 builds the SDK from this repository. The local
// xcframework must exist (rust/build-ios.sh). Without the variable, the SDK
// comes from the release that matches the plugin version.
let sdkFromSource = ["1", "true"].contains(
    ProcessInfo.processInfo.environment["SPRUCEKIT_SDK_FROM_SOURCE"]?.lowercased() ?? ""
)

// Flutter reaches this package through a symlink in ios/Flutter/ephemeral, so
// a relative path does not find the repository root. The real path of this
// file does.
let repoRoot = URL(fileURLWithPath: #filePath)
    .resolvingSymlinksInPath()
    .deletingLastPathComponent()  // sprucekit_mobile
    .deletingLastPathComponent()  // ios
    .deletingLastPathComponent()  // sprucekit_mobile (plugin root)
    .deletingLastPathComponent()  // repository root

// The identity of a path dependency is its directory name.
let sdkPackage = sdkFromSource ? repoRoot.lastPathComponent : "sprucekit-mobile"

let package = Package(
    name: "sprucekit_mobile",
    platforms: [
        .iOS(.v14)
    ],
    products: [
        .library(name: "sprucekit-mobile", targets: ["sprucekit_mobile"])
    ],
    dependencies: [
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
        sdkFromSource
            ? .package(path: repoRoot.path)
            : .package(url: "https://github.com/spruceid/sprucekit-mobile", exact: "0.25.0"),
    ],
    targets: [
        .target(
            name: "sprucekit_mobile",
            dependencies: [
                .product(name: "FlutterFramework", package: "FlutterFramework"),
                .product(name: "SpruceIDMobileSdk", package: sdkPackage),
            ]
        )
    ]
)
