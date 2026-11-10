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

# shellcheck source-path=SCRIPTDIR source=export-prefixes.sh
. "$(dirname "$0")/export-prefixes.sh"

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
# UniFFI's bindings call uniffi_*/ffi_* through JNA, by their plain names. Any
# other `external fun` is a JNI method, exported as
# Java_<package>_<class>_<method> (JNI name mangling: `_` becomes `_1`, a
# nested class's `$` becomes `_00024`). The enclosing class comes from a stack
# of object/class declarations, kept by counting braces outside `//` comments.
find "$kt_dir" -name '*.kt' -exec awk '
  function jni(s) { gsub(/_/, "_1", s); gsub(/\$/, "_00024", s); gsub(/\./, "_", s); return s }
  FNR == 1 { pkg = ""; n = 0; depth = 0; pending = "" }
  {
    line = $0
    sub(/\/\/.*/, "", line)
    if (line ~ /^[ \t]*package[ \t]/) { split(line, p, /[ \t]+/); pkg = (p[1] == "" ? p[3] : p[2]) }
    # A function header claims the next brace, so a class without a body
    # (`data class X(...)`) does not get pushed by it.
    if (line ~ /(^|[ \t])fun[ \t]/) pending = ""
    if (match(line, /(^|[ \t])(object|class)[ \t]+[A-Za-z0-9_]+/)) {
      split(substr(line, RSTART, RLENGTH), d, /[ \t]+/); pending = d[length(d)]
    }
    if (match(line, /external[ \t]+fun[ \t]+[A-Za-z0-9_]+/)) {
      split(substr(line, RSTART, RLENGTH), e, /[ \t]+/); name = e[3]
      if (name ~ /^(uniffi_|ffi_)/) print name
      else {
        cls = ""
        for (i = 1; i <= n; i++) cls = cls (i > 1 ? "$" : "") stk[i]
        print "Java_" jni(pkg) "_" jni(cls) "_" jni(name)
      }
    }
    for (i = 1; i <= length(line); i++) {
      c = substr(line, i, 1)
      if (c == "{") {
        depth++
        if (pending != "") { n++; stk[n] = pending; sd[n] = depth; pending = "" }
      } else if (c == "}") {
        if (n > 0 && sd[n] == depth) n--
        depth--
      }
    }
  }' {} + | sort -u > "$tmp/needed"

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

grep -Ev "$EXPORTED_PREFIXES" "$tmp/exported" > "$tmp/unexpected" || true
if [ -s "$tmp/unexpected" ]; then
  echo "error: $lib exports $(grep -c . "$tmp/unexpected") symbols not matching $EXPORTED_PREFIXES" >&2
  echo "(was the version script from android-version-script.sh applied? A name only some" >&2
  echo "targets define goes in that script's target_only list):" >&2
  head -20 "$tmp/unexpected" | sed 's/^/  /' >&2
  status=1
fi

echo "$(grep -c . "$tmp/exported") exported, $needed called from Kotlin, $(grep -c . "$tmp/missing" || true) missing, $(grep -c . "$tmp/unexpected" || true) unexpected"
exit $status
