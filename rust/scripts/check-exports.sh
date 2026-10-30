#!/bin/sh
# Checks that an Android libmobile_sdk_rs.so exports every native function the
# generated Kotlin bindings declare, and nothing outside the UniFFI scaffolding
# and JNI entry points (e.g. no UNIFFI_META_* statics). A missing
# symbol would only fail at runtime, when JNA registers the bindings.
#
# usage: check-exports.sh <libmobile_sdk_rs.so> <dir with the generated .kt files>
#   NM: nm to use (default: the NDK's llvm-nm if ANDROID_NDK_HOME is set,
#       else llvm-nm, else nm)
set -eu

lib=$1
kt_dir=$2

if [ -z "${NM:-}" ]; then
  for candidate in "${ANDROID_NDK_HOME:-/nonexistent}"/toolchains/llvm/prebuilt/*/bin/llvm-nm; do
    [ -x "$candidate" ] && NM=$candidate && break
  done
fi
if [ -z "${NM:-}" ]; then
  NM=$(command -v llvm-nm || echo nm)
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

"$NM" -D --defined-only "$lib" | awk 'NF == 3 { print $3 }' | sort -u > "$tmp/exported"
find "$kt_dir" -name '*.kt' -exec grep -ohE 'external fun [A-Za-z0-9_]+' {} + |
  awk '{ print $3 }' | sort -u > "$tmp/needed"

needed=$(grep -c . "$tmp/needed" || true)
if [ "$needed" -eq 0 ]; then
  echo "error: no 'external fun' declarations found under $kt_dir" >&2
  exit 1
fi

status=0
comm -23 "$tmp/needed" "$tmp/exported" > "$tmp/missing"
if [ -s "$tmp/missing" ]; then
  echo "error: $(grep -c . "$tmp/missing") symbols the Kotlin bindings call are not exported by $lib:" >&2
  sed 's/^/  /' "$tmp/missing" >&2
  status=1
fi

grep -Ev '^(uniffi_|ffi_|JNI_On|Java_)' "$tmp/exported" > "$tmp/unexpected" || true
if [ -s "$tmp/unexpected" ]; then
  echo "error: $lib exports $(grep -c . "$tmp/unexpected") symbols other than uniffi_*, ffi_*, JNI_On*, Java_*" >&2
  echo "(was the version script from android-version-script.sh applied?):" >&2
  head -20 "$tmp/unexpected" | sed 's/^/  /' >&2
  status=1
fi

echo "$(grep -c . "$tmp/exported") exported, $needed called from Kotlin, $(grep -c . "$tmp/missing" || true) missing, $(grep -c . "$tmp/unexpected" || true) unexpected"
exit $status
