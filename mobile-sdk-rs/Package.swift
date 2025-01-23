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
                "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.8.3/RustFramework.xcframework.zip",
            checksum: "dfe7efd127b4b4b80ef8332bba5d70f853c468c3f2a941ddf28d3b5d0d19a867"),
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
