#!/bin/sh
# Refreshes libs/rustls-platform-verifier-<version>.jar, the Kotlin component
# of the rustls-platform-verifier crate, after Cargo.lock moves to a new
# rustls-platform-verifier-android version (the Gradle build fails until then).
#
# The component isn't on Maven Central: upstream publishes it as an AAR in a
# Maven layout on the `maven-archive` branch of rustls/rustls-platform-verifier.
# Only its classes are needed (the AAR's manifest also sets an app-wide
# network security config, which an SDK shouldn't impose), so this takes
# classes.jar out of the AAR after checking the published SHA-1.
set -eu

cd "$(dirname "$0")"
version=$(awk '/^name = "rustls-platform-verifier-android"$/ { getline; gsub(/version = |"/, ""); print }' ../../rust/Cargo.lock)
if [ -z "$version" ] || [ "$(echo "$version" | wc -l)" -ne 1 ]; then
    echo "Expected one rustls-platform-verifier-android in rust/Cargo.lock, found: '$version'" >&2
    exit 1
fi

base="https://raw.githubusercontent.com/rustls/rustls-platform-verifier/maven-archive/android-release-support/maven/org/rustls/rustls-platform-verifier/$version"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -fsSL -o "$tmp/component.aar" "$base/rustls-platform-verifier-$version.aar"
expected=$(curl -fsSL "$base/rustls-platform-verifier-$version.aar.sha1" | cut -d' ' -f1)
actual=$(shasum -a 1 "$tmp/component.aar" | cut -d' ' -f1)
if [ "$expected" != "$actual" ]; then
    echo "SHA-1 mismatch for rustls-platform-verifier $version: expected $expected, got $actual" >&2
    exit 1
fi

unzip -q -o "$tmp/component.aar" classes.jar -d "$tmp"
rm -f libs/rustls-platform-verifier-*.jar
mkdir -p libs
mv "$tmp/classes.jar" "libs/rustls-platform-verifier-$version.jar"
echo "libs/rustls-platform-verifier-$version.jar (from AAR SHA-1 $actual)"
