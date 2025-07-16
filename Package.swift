// swift-tools-version:5.3

import PackageDescription

let package = Package(
    name: "SpruceIDMobileSdk",
    platforms: [
        .iOS(.v14)
    ],
    products: [
        .library(
            name: "SpruceIDMobileSdk",
            targets: ["SpruceIDMobileSdk"]
        ),
        .library(
            name: "SpruceIDMobileSdkRs",
            targets: ["SpruceIDMobileSdkRs"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-algorithms", from: "1.2.0")
    ],
    targets: [
        .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/sprucekit-mobile/releases/download/0.12.0/RustFramework.xcframework.zip", checksum: "b3027729962cbb03c70bc61f9d644dd77d3630d71301f7157e578e5f51e076c1"),
        .target(
            name: "SpruceIDMobileSdkRs",
            dependencies: [
                .target(name: "RustFramework")
            ],
            path: "rust/MobileSdkRs/Sources/MobileSdkRs",
            swiftSettings: [
                //.swiftLanguageMode(.v5)  // required until https://github.com/mozilla/uniffi-rs/issues/2448 is closed
            ]
        ),
        .target(
            name: "SpruceIDMobileSdk",
            dependencies: [
                .target(name: "SpruceIDMobileSdkRs"),
                .product(name: "Algorithms", package: "swift-algorithms"),
            ],
            path: "./ios/MobileSdk/Sources/MobileSdk",
            swiftSettings: [
                //.swiftLanguageMode(.v5)  // some of our code isn't concurrent-safe (e.g. OID4VCI.swift)
            ]
        ),
        .testTarget(
            name: "SpruceIDMobileSdkTests",
            dependencies: ["SpruceIDMobileSdk"],
            path: "./ios/MobileSdk/Tests/MobileSdkTests"
        ),
    ]
)
