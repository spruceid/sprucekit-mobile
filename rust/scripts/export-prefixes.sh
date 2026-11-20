# Symbols the Android .so keeps exported: the UniFFI scaffolding (uniffi_*,
# ffi_*) and JNI entry points (JNI_On*, Java_*), as an extended regex.
# Sourced by android-version-script.sh and check-exports.sh.
# shellcheck shell=sh disable=SC2034
EXPORTED_PREFIXES='^(uniffi_|ffi_|JNI_On|Java_)'
