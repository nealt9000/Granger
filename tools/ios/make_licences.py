"""Writes the iPhone's open-source licences screen data from tools/ios/licences/ (Plan I7 call 8).

    python3 -I tools/ios/make_licences.py            # write ios/Granger/Resources/Licences.json
    python3 -I tools/ios/make_licences.py --check    # exit 1 if a library isn't listed or the file is out of date

licences/licences.json lists every library and asset the app bundles; licences/texts/ holds each licence text once.
--check fails when an Xcode package (the project's Package.resolved, so Readium's own packages count too) or a vendor
script (tools/ios/fetch-*.sh, build-*.sh: what lands in ios/Vendor) has no entry, when an entry names a package or
script that's gone, when a text is missing, or when Licences.json isn't what this would write. GrangerTests runs
--check before every test build (project.yml), as it runs make_text_ids.py --check.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "tools/ios/licences"
OUT = ROOT / "ios/Granger/Resources/Licences.json"
RESOLVED = ROOT / "ios/Granger/Granger.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"


def build():
    listed = json.loads((SRC / "licences.json").read_text())["libraries"]
    problems = []
    keys = sorted({k for lib in listed for k in lib["texts"]})
    texts = {}
    for k in keys:
        f = SRC / "texts" / f"{k}.txt"
        if f.is_file():
            texts[k] = f.read_text().strip() + "\n"
        else:
            problems.append(f"licence text {f.relative_to(ROOT)} is missing")
    libraries = [
        {k: lib[k] for k in ("name", "version", "licence", "url", "note", "texts") if k in lib}
        for lib in listed
    ]
    data = json.dumps({"libraries": libraries, "texts": texts}, ensure_ascii=False, indent=1, sort_keys=True) + "\n"
    return listed, data, problems


def sources(listed):
    """What --check compares the list with: the Xcode packages and the vendor scripts, against the entries' 'from'."""
    problems = []
    pins = {p["identity"] for p in json.loads(RESOLVED.read_text())["pins"]}
    scripts = {p.name for p in (ROOT / "tools/ios").glob("*.sh") if p.name.startswith(("fetch-", "build-"))}
    froms = {lib["from"] for lib in listed}
    for pin in sorted(pins - {f.removeprefix("package:") for f in froms if f.startswith("package:")}):
        problems.append(f"Xcode package '{pin}' has no entry in tools/ios/licences/licences.json")
    for script in sorted(scripts - {f.removeprefix("vendor:") for f in froms if f.startswith("vendor:")}):
        problems.append(f"vendor script tools/ios/{script} brings a library with no entry in tools/ios/licences/licences.json")
    for f in sorted(froms):
        kind, _, name = f.partition(":")
        if kind == "package" and name not in pins:
            problems.append(f"licences.json lists package '{name}', which the project no longer has")
        elif kind == "vendor" and name not in scripts:
            problems.append(f"licences.json lists tools/ios/{name}, which is gone")
        elif kind not in ("package", "vendor", "app", "kotlin", "asset", "voices"):
            problems.append(f"licences.json: unknown 'from' {f!r}")
    return problems


def main():
    check = "--check" in sys.argv
    listed, data, problems = build()
    problems += sources(listed)
    if check:
        if not OUT.is_file() or OUT.read_text() != data:
            problems.append(f"{OUT.relative_to(ROOT)} is out of date: run python3 tools/ios/make_licences.py")
        for p in problems:
            print(f"error: {p}")
        sys.exit(1 if problems else 0)
    if problems:
        sys.exit("\n".join(problems))
    OUT.write_text(data)
    print(f"{OUT.relative_to(ROOT)}: {len(listed)} libraries")


if __name__ == "__main__":
    main()
