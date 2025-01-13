// swift-tools-version:5.5
// The swift-tools-version declares the minimum version of Swift required to build this package.
// Swift Package: SpruceIDMobileSdkRs

import PackageDescription

let package = Package(
    name: "SpruceIDMobileSdkRs",
    platforms: [
        .iOS(.v14),
        .macOS(.v10_15),
    ],
    products: [
        .library(
            name: "SpruceIDMobileSdkRs",
            targets: ["SpruceIDMobileSdkRs"]
        )
    ],
    dependencies: [],
    targets: [
        .binaryTarget(
            name: "RustFramework",
            url:
                "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.4.3/RustFramework.xcframework.zip",
            checksum: "bd273b22e74532c6bb2ff229e43083ad1df67c431b5e96dda64c505beba896b4"),
        // .binaryTarget(name: "RustFramework", path: "./MobileSdkRs/RustFramework.xcframework"),
        .target(
            name: "SpruceIDMobileSdkRs",
            dependencies: [
                .target(name: "RustFramework")
            ],
            path: "./MobileSdkRs/Sources/MobileSdkRs"
        ),
    ]
)
