#!/usr/bin/env python3
"""List the git dependencies that moved between two Cargo.lock files.

Usage: upstream-changes.py OLD_LOCK NEW_LOCK [--commits]

Writes a Markdown section to stdout. With --commits, it asks the GitHub
compare API (through `gh`) for the commit subjects of each move.

The cd workflow adds this section to the release notes, with the Cargo.lock
of the previous release tag as OLD_LOCK. To preview the next release:

    git show <last tag>:rust/Cargo.lock > /tmp/old.lock
    rust/scripts/upstream-changes.py /tmp/old.lock rust/Cargo.lock --commits

Crates from crates.io are not listed: all SpruceID crates come from git.
"""
import json
import subprocess
import sys
import tomllib
from collections import defaultdict

MAX_SUBJECTS = 10
MAX_CRATES = 3


def git_pins(path):
    """Map (repo, crate) to a frozenset of (ref, commit) for git sources."""
    with open(path, "rb") as f:
        lock = tomllib.load(f)
    pins = defaultdict(set)
    for pkg in lock.get("package", []):
        src = pkg.get("source", "")
        if not src.startswith("git+"):
            continue
        url, _, commit = src[len("git+"):].partition("#")
        repo, _, query = url.partition("?")
        kind, _, value = query.partition("=")
        ref = f"{kind} {value.replace('%2F', '/')}" if query else "default branch"
        if kind == "rev":
            ref = "rev"  # the commit already names a rev pin
        pins[(repo.removesuffix(".git"), pkg["name"])].add((ref, commit))
    return {k: frozenset(v) for k, v in pins.items()}


def compare(repo, old, new):
    """Return (status, ahead_by, subjects) from the GitHub compare API."""
    slug = repo.removeprefix("https://github.com/")
    r = subprocess.run(["gh", "api", f"repos/{slug}/compare/{old}...{new}"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        return None, None, []
    data = json.loads(r.stdout)
    subjects = [c["commit"]["message"].splitlines()[0] for c in data.get("commits", [])]
    return data.get("status"), data.get("ahead_by"), subjects


def pin_text(pin):
    ref, commit = pin
    return f"`{commit[:7]}`" if ref == "rev" else f"{ref} `{commit[:7]}`"


def crate_text(names):
    shown = ", ".join(f"`{n}`" for n in names[:MAX_CRATES])
    rest = len(names) - MAX_CRATES
    return f"{shown} and {rest} more crates" if rest > 0 else shown


def move_lines(repo, old_pin, new_pin, with_commits):
    a, b = old_pin[1], new_pin[1]
    if old_pin[0] == new_pin[0] and old_pin[0] != "rev":
        text = f"{old_pin[0]}, `{a[:7]}` to `{b[:7]}`"
    else:
        text = f"{pin_text(old_pin)} to {pin_text(new_pin)}"
    text += f" ([compare]({repo}/compare/{a[:12]}...{b[:12]}))"
    if not with_commits:
        return text, []
    status, ahead, subjects = compare(repo, a, b)
    if status == "ahead":
        text += f", {ahead} commit{'s' if ahead != 1 else ''}"
        extra = [f"  - {s}" for s in subjects[-MAX_SUBJECTS:]]
        if len(subjects) > MAX_SUBJECTS:
            extra.append(f"  - and {len(subjects) - MAX_SUBJECTS} more")
        return text, extra
    if status in ("diverged", "behind"):
        return text + ", **history rewritten or moved back**: check the compare view", []
    return text + ", compare not available", []


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    with_commits = "--commits" in sys.argv
    old, new = git_pins(args[0]), git_pins(args[1])

    # Group the crates of one repo that changed in the same way.
    groups = defaultdict(list)
    for key in sorted(set(old) | set(new)):
        a, b = old.get(key, frozenset()), new.get(key, frozenset())
        if a != b:
            groups[(key[0], a, b)].append(key[1])

    print("## Upstream dependencies\n")
    if not groups:
        print("No git dependency changed.")
        return
    for (repo, a, b), names in sorted(groups.items(), key=lambda g: g[0][0]):
        # The crate named like its repo first (ssi), then the shortest names.
        base = repo.rsplit("/", 1)[-1].removesuffix("-rs")
        names.sort(key=lambda n: (n.replace("_", "-") != base, len(n), n))
        head = f"- **{repo.removeprefix('https://github.com/')}**: {crate_text(names)}"
        if not a:
            print(f"{head}: added at {', '.join(map(pin_text, sorted(b)))}")
        elif not b:
            print(f"{head}: removed (was {', '.join(map(pin_text, sorted(a)))})")
        elif len(a) == 1 and len(b) == 1:
            text, extra = move_lines(repo, next(iter(a)), next(iter(b)), with_commits)
            print(f"{head}: {text}")
            for line in extra:
                print(line)
        else:
            print(f"{head}: {', '.join(map(pin_text, sorted(a)))} "
                  f"became {', '.join(map(pin_text, sorted(b)))}")


if __name__ == "__main__":
    main()
