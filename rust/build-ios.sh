#!/usr/bin/env bash
# Build the iOS Swift package: cross-compile the Rust workspace, generate the
# UniFFI Swift bindings, and assemble MobileSdkRs/RustFramework.xcframework.
#
# The workspace contains two UniFFI crates (mobile-sdk-rs and mobile-toolkit),
# each with its own C module (RustFramework and mobile_toolkitFFI — the names
# must stay distinct or uniffi's swift test harness fails on a duplicate module
# declaration). Bindings are generated in uniffi library mode: one Swift file,
# one header, and one modulemap per crate. Both modulemaps are installed as a
# single top-level Headers/module.modulemap (concatenating distinct modules is
# valid — uniffi's own test harness does the same), which clang auto-discovers
# for either `import`. cargo-swift is not used — it only supports single-crate
# libraries and buries the modulemap where only one module is discoverable.
#
# Usage: ./build-ios.sh [--simulator-only] [--debug]
#   --simulator-only  build only the arm64-simulator slice (used by CI)
#   --debug           build the dev profile instead of release (faster local iteration)
set -euo pipefail
cd "$(dirname "$0")"

simulator_only=false
profile=release
cargo_flags=(--release)
for arg in "$@"; do
  case "$arg" in
    --simulator-only) simulator_only=true ;;
    --debug)
      profile=debug
      cargo_flags=()
      ;;
    *)
      echo "unknown argument: $arg" >&2
      echo "usage: $0 [--simulator-only] [--debug]" >&2
      exit 1
      ;;
  esac
done

lib=libmobile_sdk_rs.a

if "$simulator_only"; then
  targets=(aarch64-apple-ios-sim)
else
  targets=(aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios)
fi

for target in "${targets[@]}"; do
  cargo build ${cargo_flags[@]+"${cargo_flags[@]}"} --target "$target"
done

# Any built slice works as the bindgen metadata source.
bindgen_lib="target/${targets[0]}/$profile/$lib"

gen_dir=target/swift-bindgen
rm -rf "$gen_dir" target/ios-headers
cargo run --bin uniffi-bindgen -- generate --library "$bindgen_lib" \
  --language swift --no-format --out-dir "$gen_dir"

mv "$gen_dir"/*.swift MobileSdkRs/Sources/MobileSdkRs/
mkdir -p target/ios-headers
mv "$gen_dir"/*.h target/ios-headers/
# awk 1 normalizes missing trailing newlines so the module blocks don't run together.
awk 1 "$gen_dir"/*.modulemap > target/ios-headers/module.modulemap

xcframework_args=()
if "$simulator_only"; then
  xcframework_args+=(-library "target/aarch64-apple-ios-sim/$profile/$lib" -headers target/ios-headers)
else
  mkdir -p "target/universal-ios/$profile"
  lipo -create \
    "target/aarch64-apple-ios-sim/$profile/$lib" \
    "target/x86_64-apple-ios/$profile/$lib" \
    -output "target/universal-ios/$profile/$lib"
  xcframework_args+=(-library "target/aarch64-apple-ios/$profile/$lib" -headers target/ios-headers)
  xcframework_args+=(-library "target/universal-ios/$profile/$lib" -headers target/ios-headers)
fi

rm -rf MobileSdkRs/RustFramework.xcframework
xcodebuild -create-xcframework "${xcframework_args[@]}" \
  -output MobileSdkRs/RustFramework.xcframework

echo "Built MobileSdkRs/RustFramework.xcframework ($profile$("$simulator_only" && echo ", simulator-only"))"
