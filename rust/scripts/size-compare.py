#!/usr/bin/env python3
"""Compare native library sizes (from lib-size.sh) with the main baseline.

Prints a Markdown table (also appended to $GITHUB_STEP_SUMMARY in CI), writes
all current sizes to one JSON file (the baseline artifact on main), and exits
with 1 if a library grew past the failure threshold, when one is set.

Thresholds, from the environment (set in .github/workflows/ci.yml):
  SIZE_WARN_PCT    warn above this growth in percent      (default 1)
  SIZE_WARN_BYTES  warn above this growth in bytes        (default 100000)
  SIZE_FAIL_PCT    fail above this growth in percent      (default: unset,
                   never fail)

Usage: size-compare.py <current-dir> <baseline.json|missing> <merged-out.json>
"""

import json
import os
import sys
from pathlib import Path


def load(path):
    data = json.loads(Path(path).read_text())
    return data if isinstance(data, list) else [data]


def mb(n):
    return f"{n / 1e6:.2f} MB"


def delta(new, old):
    d = new - old
    return f"{d:+,} B ({d / old:+.2%})" if old else f"{d:+,} B"


def annotate(level, message):
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::{level}::{message}", file=sys.stderr)
    else:
        print(f"{level}: {message}", file=sys.stderr)


def main():
    current_dir, baseline_path, merged_path = sys.argv[1:4]
    warn_pct = float(os.environ.get("SIZE_WARN_PCT", "1"))
    warn_bytes = int(os.environ.get("SIZE_WARN_BYTES", "100000"))
    fail_pct = os.environ.get("SIZE_FAIL_PCT", "").strip()
    fail_pct = float(fail_pct) if fail_pct else None

    current = sorted(
        (e for p in Path(current_dir).glob("*.json") for e in load(p)),
        key=lambda e: e["target"],
    )
    baseline = {}
    if Path(baseline_path).is_file():
        baseline = {e["target"]: e for e in load(baseline_path)}

    # A target whose job failed keeps its previous entry, so a partial run on
    # main doesn't drop it from the next baseline.
    measured = {e["target"] for e in current}
    kept = [e for t, e in sorted(baseline.items()) if t not in measured]
    Path(merged_path).write_text(json.dumps(current + kept, indent=2) + "\n")
    if not current:
        # The jobs that measure failed; they report their own errors.
        print(f"No size reports in {current_dir}.")
        return

    lines = ["## Native library size", ""]
    if baseline:
        commits = sorted({e["commit"][:8] for e in baseline.values()})
        lines.append(f"Compared with `main` at {', '.join(f'`{c}`' for c in commits)}.")
    else:
        lines.append("No `main` baseline found: sizes only.")
    failure = f"failure above +{fail_pct:g}%" if fail_pct is not None else "no failure threshold"
    lines += [
        f"Warning above +{warn_pct:g}% or +{warn_bytes:,} B, {failure}.",
        "",
        "| Target | Size | gzip -9 | Δ size | Δ gzip | Status |",
        "|---|---:|---:|---:|---:|---|",
    ]

    failed = False
    for e in current:
        target, size, gz = e["target"], e["bytes"], e["gzip_bytes"]
        base = baseline.get(target)
        if base is None:
            lines.append(f"| {target} | {mb(size)} | {mb(gz)} | – | – | no baseline |")
            continue
        growth = size - base["bytes"]
        pct = 100 * growth / base["bytes"]
        status = "ok"
        if fail_pct is not None and pct > fail_pct:
            status, failed = "**fail**", True
            annotate("error", f"{target} grew by {delta(size, base['bytes'])}, above +{fail_pct:g}%")
        elif pct > warn_pct or growth > warn_bytes:
            status = "warning"
            annotate("warning", f"{target} grew by {delta(size, base['bytes'])}")
        lines.append(
            f"| {target} | {mb(size)} | {mb(gz)} | {delta(size, base['bytes'])} "
            f"| {delta(gz, base['gzip_bytes'])} | {status} |"
        )

    lines += [
        "",
        "Android: stripped `.so` (arm64-v8a). iOS: device `.a`, before the app link strips it.",
        "Toolchains: " + "; ".join(f"{e['target']}: {e['rustc']}, {e['toolchain']}" for e in current),
        "",
    ]
    report = "\n".join(lines)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(report + "\n")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
