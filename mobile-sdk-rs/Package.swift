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
        .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.3.0/RustFramework.xcframework.zip", checksum: "d2f3ed0ab90d64f684baf8b33595f205e741953fe1a034548282099470fa764b"),
        //.binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.3.0/RustFramework.xcframework.zip", checksum: "d2f3ed0ab90d64f684baf8b33595f205e741953fe1a034548282099470fa764b"),
        .target(
            name: "SpruceIDMobileSdkRs",
            dependencies: [
                .target(name: "RustFramework")
            ],
            path: "./MobileSdkRs/Sources/MobileSdkRs"
        )
    ]
)
