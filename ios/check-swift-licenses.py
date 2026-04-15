#!/usr/bin/env python3
"""
Check that all Swift dependencies (SPM + CocoaPods) have approved licenses.

1. Parses Package.resolved to find all SPM packages and validates their licenses.
2. Parses .podspec files for external dependencies and validates they are known.

Fails if any dependency is unmapped (forcing manual review) or has a disallowed license.

The license allowlist mirrors rust/deny.toml. If the allowlist changes, update both.
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

ALLOWED_LICENSES = {
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "CC0-1.0",
    "CDLA-Permissive-2.0",
    "ISC",
    "MIT",
    "MPL-2.0",
    "Unicode-3.0",
}

# Known SPM packages and their SPDX license identifiers.
# When adding a new SPM dependency, verify its license and add it here.
SPM_LICENSES = {
    "swift-algorithms": "Apache-2.0",
    "swift-numerics": "Apache-2.0",
    "SQLite.swift": "MIT",
    "RiveRuntime": "MIT",
}

# Mapping from CocoaPods dependency names to SPM package names.
# Internal pods (SpruceIDMobileSdk*, Flutter) are skipped automatically.
COCOAPODS_TO_SPM = {
    "SwiftAlgorithms": "swift-algorithms",
}

INTERNAL_POD_PREFIXES = ("SpruceIDMobileSdk", "Flutter")


def check_spm_packages() -> bool:
    """Check all SPM packages in Package.resolved have allowed licenses."""
    print("=== Checking SPM dependencies (Package.resolved) ===")

    resolved_path = REPO_ROOT / "Package.resolved"
    if not resolved_path.exists():
        print(f"ERROR: Package.resolved not found at {resolved_path}")
        return False

    with open(resolved_path) as f:
        data = json.load(f)

    version = data.get("version", 1)
    if version == 1:
        pins = data["object"]["pins"]
        packages = [pin["package"] for pin in pins]
    elif version in (2, 3):
        pins = data["pins"]
        packages = [pin.get("identity", pin.get("package", "")) for pin in pins]
    else:
        print(f"ERROR: Unknown Package.resolved version: {version}")
        return False

    ok = True
    for pkg in packages:
        if not pkg:
            continue
        if pkg not in SPM_LICENSES:
            print(f"  ERROR: Unknown SPM package '{pkg}' -- verify its license and add to SPM_LICENSES in {__file__}")
            ok = False
        else:
            license_id = SPM_LICENSES[pkg]
            if license_id in ALLOWED_LICENSES:
                print(f"  OK: {pkg} ({license_id})")
            else:
                print(f"  ERROR: {pkg} has license '{license_id}' which is not in the allowlist")
                ok = False

    return ok


def check_podspec_deps() -> bool:
    """Check that external CocoaPods dependencies are known and license-verified."""
    print()
    print("=== Checking CocoaPods podspec dependencies ===")

    # Collect podspec files from repo root and flutter/ios/
    podspec_files = list(REPO_ROOT.glob("*.podspec"))
    flutter_ios = REPO_ROOT / "flutter" / "ios"
    if flutter_ios.exists():
        podspec_files.extend(flutter_ios.glob("*.podspec"))

    dep_pattern = re.compile(r"""^\s*(?:spec|s)\.dependency\s+['"]([^'"]+)['"]""")

    ok = True
    for podspec in sorted(podspec_files):
        podspec_name = podspec.name
        with open(podspec) as f:
            for line in f:
                m = dep_pattern.match(line)
                if not m:
                    continue
                dep = m.group(1)

                if any(dep.startswith(prefix) for prefix in INTERNAL_POD_PREFIXES):
                    print(f"  SKIP: {podspec_name} -> {dep} (internal)")
                    continue

                if dep not in COCOAPODS_TO_SPM:
                    print(f"  ERROR: Unknown external CocoaPods dependency '{dep}' in {podspec_name}")
                    print(f"         Add it to COCOAPODS_TO_SPM mapping in {__file__} after verifying its license")
                    ok = False
                else:
                    spm_name = COCOAPODS_TO_SPM[dep]
                    if spm_name not in SPM_LICENSES:
                        print(f"  ERROR: CocoaPods dep '{dep}' maps to SPM '{spm_name}' but no license is recorded")
                        ok = False
                    else:
                        print(f"  OK: {podspec_name} -> {dep} (maps to SPM '{spm_name}', {SPM_LICENSES[spm_name]})")

    return ok


def main() -> int:
    spm_ok = check_spm_packages()
    pods_ok = check_podspec_deps()

    print()
    if spm_ok and pods_ok:
        print("All Swift dependency licenses OK.")
        return 0
    else:
        print("License check FAILED. See errors above.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
