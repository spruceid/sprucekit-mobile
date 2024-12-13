// swift-tools-version:5.5
// The swift-tools-version declares the minimum version of Swift required to build this package.
// Swift Package: SpruceIDMobileSdkRs

import PackageDescription;

let package = Package(
    name: "SpruceIDMobileSdkRs",
    platforms: [
        .iOS(.v14),
        .macOS(.v10_15)
    ],
    products: [
        .library(
            name: "SpruceIDMobileSdkRs",
            targets: ["SpruceIDMobileSdkRs"]
        )
    ],
    dependencies: [ ],
    targets: [
        .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.4.1/RustFramework.xcframework.zip", checksum: "e51252ad3822a2a1b4e200b858f3a469cfed4d44df29a7ceafb5d68e3c35c947"),
        // .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.4.1/RustFramework.xcframework.zip", checksum: "e51252ad3822a2a1b4e200b858f3a469cfed4d44df29a7ceafb5d68e3c35c947"),
        .target(
            name: "SpruceIDMobileSdkRs",
            dependencies: [
                .target(name: "RustFramework")
            ],
            path: "./MobileSdkRs/Sources/MobileSdkRs"
        )
    ]
)
