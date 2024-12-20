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
    // .binaryTarget(name: "RustFramework", url: "https://github.com/spruceid/mobile-sdk-rs/releases/download/0.4.2/RustFramework.xcframework.zip", checksum: "ad8961c45530572689187d1bc1b4e7fd96bb04f6a12ff32fd49e3d4f7fa680e8"),
    .binaryTarget(name: "RustFramework", path: "./MobileSdkRs/RustFramework.xcframework"),
    .target(
      name: "SpruceIDMobileSdkRs",
      dependencies: [
        .target(name: "RustFramework")
      ],
      path: "./MobileSdkRs/Sources/MobileSdkRs"
    ),
  ]
)
