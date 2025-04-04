// swift-tools-version: 6.0

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
    .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/sprucekit-mobile/releases/download/0.10.2/RustFramework.xcframework.zip", checksum: "70b40914fcbff343584ab4e7ffa7724ac987d04339d50ef510d3ad8f7d4d92f4"),
    .target(
      name: "SpruceIDMobileSdkRs",
      dependencies: [
        .target(name: "RustFramework")
      ],
      path: "rust/MobileSdkRs/Sources/MobileSdkRs",
      swiftSettings: [
        .swiftLanguageMode(.v5)  // required until https://github.com/mozilla/uniffi-rs/issues/2448 is closed
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
        .swiftLanguageMode(.v5)  // some of our code isn't concurrent-safe (e.g. OID4VCI.swift)
      ]
    ),
    .testTarget(
      name: "SpruceIDMobileSdkTests",
      dependencies: ["SpruceIDMobileSdk"],
      path: "./ios/MobileSdk/Tests/MobileSdkTests"
    ),
  ]
)
