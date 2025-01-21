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
                "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.8.1/RustFramework.xcframework.zip",
            checksum: "68858dc18cc32a608655e007b4d0ffc65fb1c467198c64474cf192a38e6839df"),
        // .binaryTarget(name: "RustFramework", path: "MobileSdkRs/RustFramework.xcframework"),
        .target(
            name: "SpruceIDMobileSdkRs",
            dependencies: [
                .target(name: "RustFramework")
            ],
            path: "./MobileSdkRs/Sources/MobileSdkRs"
        ),
    ]
)
