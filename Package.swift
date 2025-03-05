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
    .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/sprucekit-mobile/releases/download/0.10.1/RustFramework.xcframework.zip", checksum: "8af996c88c9050b1798d456f6259adf83247eb9bb08230cededa84e65ea5c59f"),
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
      path: "./ios/MobileSdk/Sources/MobileSdk"
    ),
    .testTarget(
      name: "SpruceIDMobileSdkTests",
      dependencies: ["SpruceIDMobileSdk"],
      path: "./ios/MobileSdk/Tests/MobileSdkTests"
    ),
  ]
)
