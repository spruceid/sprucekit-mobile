#!/usr/bin/env bash
# Build the release native library for one target, exactly as the release
# does, and write its size as JSON: {target, bytes, gzip_bytes, commit, ...}.
#
#   aarch64-linux-android  libmobile_sdk_rs.so via cargo-ndk into a jniLibs
#                          directory, as `buildCargoNdk` in
#                          android/MobileSdkRs/build.gradle; then stripped
#                          with `llvm-strip --strip-unneeded`, as the Android
#                          Gradle plugin does when it packages the app.
#                          Needs cargo-ndk and ANDROID_NDK_HOME.
#   aarch64-apple-ios      libmobile_sdk_rs.a, as rust/build-ios.sh (device
#                          slice). Unstripped: Xcode strips at app link time.
#
# Usage: rust/scripts/lib-size.sh <target> <out.json> [library]
#   library: measure this file instead of building (in CI, the jniLibs copy
#            the android job built; locally, for comparisons)
set -euo pipefail
cd "$(dirname "$0")/.."

target=$1
out=$2
lib=${3:-}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

case "$target" in
  aarch64-linux-android)
    if [ -z "$lib" ]; then
      min_sdk=$(sed -n 's/^ *minSdk \([0-9]*\).*/\1/p' ../android/MobileSdkRs/build.gradle)
      # Through -o, like Gradle: cargo-ndk strips the copy, which changes the
      # final size slightly (by a few hundred bytes).
      cargo ndk -t arm64-v8a --platform "$min_sdk" -o "$tmp/jniLibs" \
        rustc --lib --crate-type cdylib --release
      lib=$tmp/jniLibs/arm64-v8a/libmobile_sdk_rs.so
    fi
    : "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the NDK}"
    strip=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
    measured=$tmp/stripped.so
    "$strip" --strip-unneeded -o "$measured" "$lib"
    toolchain="NDK $(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties")"
    ;;
  aarch64-apple-ios)
    if [ -z "$lib" ]; then
      cargo rustc --lib --crate-type staticlib --release --target "$target"
      lib=target/$target/release/libmobile_sdk_rs.a
    fi
    measured=$lib
    toolchain="Xcode $(xcodebuild -version | sed -n 's/^Xcode //p')"
    ;;
  *)
    echo "unknown target: $target (expected aarch64-linux-android or aarch64-apple-ios)" >&2
    exit 1
    ;;
esac

bytes=$(wc -c <"$measured" | tr -d ' ')
gzip_bytes=$(gzip -9 -n -c <"$measured" | wc -c | tr -d ' ')
commit=${GITHUB_SHA:-$(git rev-parse HEAD)}

mkdir -p "$(dirname "$out")"
cat >"$out" <<EOF
{"target": "$target", "bytes": $bytes, "gzip_bytes": $gzip_bytes, "commit": "$commit", "rustc": "$(rustc --version)", "toolchain": "$toolchain"}
EOF
cat "$out"
