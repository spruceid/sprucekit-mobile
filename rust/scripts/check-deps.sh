#!/usr/bin/env bash
# Dependency-graph guard for the mobile targets. No build: `cargo tree` only
# reads the lockfile, so the targets' std doesn't need to be installed.
#
# - Fails if OpenSSL or native-tls is in the Android or iOS graph. reqwest's
#   default features bring them back (and link a vendored OpenSSL on Android);
#   the SDK uses rustls.
# - Warns if the HTTP/TLS stack is there in more than one version.
#
# Usage (from anywhere): rust/scripts/check-deps.sh
set -euo pipefail
cd "$(dirname "$0")/.."

targets=(aarch64-linux-android aarch64-apple-ios)
forbidden=(openssl-sys native-tls)
single=(reqwest hyper h2 http rustls)

# GitHub annotations in CI, plain text locally.
error() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::error::$*"; else echo "error: $*"; fi; }
warning() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::warning::$*"; else echo "warning: $*"; fi; }

status=0
for target in "${targets[@]}"; do
  tree=(cargo tree --locked -p mobile-sdk-rs --edges 'normal,build' --target "$target")
  # "name version" per package, deduplicated.
  packages=$("${tree[@]}" --prefix none --format '{p}' | awk '{print $1, $2}' | sort -u)

  for crate in "${forbidden[@]}"; do
    if grep -q "^$crate " <<<"$packages"; then
      error "$crate is in the $target dependency graph"
      "${tree[@]}" --invert "$crate" --depth 4 | sed 's/^/    /'
      status=1
    fi
  done

  for crate in "${single[@]}"; do
    versions=$(awk -v c="$crate" '$1 == c {print $2}' <<<"$packages" | paste -sd ' ' -)
    if [ "$(wc -w <<<"$versions")" -gt 1 ]; then
      warning "$crate has more than one version in the $target dependency graph: $versions"
    fi
  done

  echo "$target: $(wc -l <<<"$packages" | tr -d ' ') packages checked"
done

exit "$status"
