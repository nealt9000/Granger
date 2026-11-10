#!/usr/bin/env python3
"""The upkeep loop, step 1 (docs/superpowers/specs/2026-11-09-upkeep-loop.md): find what needs doing, change nothing.

  upkeep.py check [--out DIR] [--published catalog/catalog.json] [--curated tools/catalog-gen/curated.json]

`check` builds a candidate catalog against the published one (`catalog_gen.py build --previous`, so packs whose newer
snapshot isn't safe yet keep their pins), compares the two, checks every published link and the tier budgets, and
writes to DIR (default ~/granger-upkeep/<date>):

  findings.json   one finding per thing to know or do: {kind, group, ids, summary, action}
  report.md       the same, short enough to read on a phone, grouped: needs Neal, ready, review, waiting
  candidate.json  the candidate catalog (unsigned), for the apply playbook; candidate-report.txt is its build report

Each finding's `kind` is one of KINDS, and its `action` is that kind's playbook in tools/upkeep/README.md. An agent
running the loop takes one finding at a time and follows its playbook; it never signs, uploads, pushes or uses the
GPU. Run with ~/.venvs/granger-catalog/bin/python (catalog_gen's libraries).
"""
import argparse
import datetime
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
sys.path.insert(0, str(REPO_ROOT / "tools/catalog-gen"))
import catalog_gen as cg  # noqa: E402

GROUPS = ("needs-neal", "ready", "review", "waiting")
GROUP_TITLES = {"needs-neal": "Needs Neal", "ready": "Ready to apply", "review": "Review", "waiting": "Waiting"}

# kind: (group, playbook). The playbooks are spelled out in README.md under the same names.
KINDS = {
    "snapshot-ready": ("ready", "apply-candidate"),
    "pack-added": ("ready", "apply-candidate"),
    "copy-changed": ("review", "review-copy"),
    "snapshot-held-mirror": ("waiting", "none: a later run takes it once every mirror has the file"),
    "snapshot-held-vectors": ("needs-neal", "rebuild-vectors (Mac GPU: ask Neal)"),
    "snapshot-held-timeindex": ("needs-neal", "rebuild-timeindex"),
    "snapshot-forced": ("needs-neal", "rebuild-vectors / rebuild-timeindex: the old file is gone, so the pack moved anyway"),
    "derived-dropped": ("needs-neal", "rebuild-vectors / rebuild-timeindex"),
    "pack-removed": ("needs-neal", "replace-or-drop"),
    "dead-link": ("needs-neal", "replace-or-drop"),
    "candidate-dead-link": ("needs-neal", "replace-or-drop"),
    "budget-over": ("needs-neal", "budget-call"),
    "repo-change": ("review", "review-repo-change"),
    "build-failed": ("needs-neal", "fix-build"),
}


def finding(kind, ids, summary):
    group, action = KINDS[kind]
    return {"kind": kind, "group": group, "ids": sorted(ids), "summary": summary, "action": action}


def compare(published, candidate, holds):
    """Findings from the candidate catalog against the published one, and the hold records the build made."""
    out = []
    old = {p["id"]: p for p in published.get("packs", [])}
    new = {p["id"]: p for p in candidate.get("packs", [])}
    for h in holds:
        if not h["held"]:
            out.append(finding("snapshot-forced", h["ids"], f"{h['zim']}: moved {h['from']} → {h['to']} because "
                                                            f"{h['from']} no longer answers; {', '.join(h['reasons'])}"))
            continue
        for reason, kind in (("vectors", "snapshot-held-vectors"), ("timeIndex", "snapshot-held-timeindex"),
                             ("mirror", "snapshot-held-mirror")):
            if reason in h["reasons"]:
                out.append(finding(kind, h["ids"], f"{h['zim']}: {h['to']} waits, {h['from']} kept"))
    moved = {}
    for pid in new.keys() & old.keys():
        a, b = old[pid], new[pid]
        if a.get("sha256") != b.get("sha256"):
            moved.setdefault(b.get("zimName") or pid, []).append(pid)
        for field in ("title", "description"):
            if a.get(field) != b.get(field):
                out.append(finding("copy-changed", [pid], f"{pid} {field}: {a.get(field)!r} → {b.get(field)!r}"))
        for key, label in (("vectors", "search vectors"), ("timeIndex", "time index")):
            if a.get(key) and not b.get(key):
                out.append(finding("derived-dropped", [pid], f"{pid} loses its {label}"))
    forced = {pid for h in holds if not h["held"] for pid in h["ids"]}
    for zim, ids in sorted(moved.items()):
        if set(ids) & forced:
            continue
        p = new[ids[0]]
        gb = sum(new[i]["sizeBytes"] - old[i]["sizeBytes"] for i in ids) / 1e9
        out.append(finding("snapshot-ready", ids, f"{zim}: {old[ids[0]].get('snapshotDate')} → {p.get('snapshotDate')}"
                                                  f" ({gb:+.2f} GB)"))
    for pid in sorted(new.keys() - old.keys()):
        out.append(finding("pack-added", [pid], f"{pid}: {new[pid].get('title')} ({new[pid]['sizeBytes'] / 1e9:.2f} GB)"))
    for pid in sorted(old.keys() - new.keys()):
        out.append(finding("pack-removed", [pid], f"{pid}: {old[pid].get('title')} is no longer in Kiwix's catalog"))
    rest = lambda c: {k: v for k, v in c.items() if k not in ("packs", "generatedAt")}  # noqa: E731
    if rest(published) != rest(candidate):
        keys = sorted(k for k in rest(published).keys() | rest(candidate).keys()
                      if rest(published).get(k) != rest(candidate).get(k))
        out.append(finding("repo-change", [], f"the catalog's {', '.join(keys)} differ from the published one "
                                              "(a repo change since the last signing)"))
    return out


def budget_findings(catalog):
    out = []
    for language, budget in cg.TIER_BUDGETS.items():
        sizes = cg.tier_sizes(catalog, language).get("All")
        if not sizes:
            continue
        for name, size, limit in (("Efficient", sizes[0], budget[0]), ("Comprehensive", sizes[1], budget[1])):
            if size >= limit:
                out.append(finding("budget-over", [], f"{language} {name} is {size / 1e9:.1f} GB, over its "
                                                      f"{limit / 1e9:.0f} GB budget"))
    return out


def link_findings(published, candidate, status=None, sleep=None):
    """Dead links in the published catalog, and in the candidate's new links."""
    kw = {"status": status or cg.http_status}
    if sleep:
        kw["sleep"] = sleep
    old_urls = cg.catalog_urls(published)
    owners = {}
    for kind in ("packs", "models", "books", "voices"):
        for e in published.get(kind, []) + candidate.get(kind, []):
            for u in e.get("urls", []) + [e.get("url"), (e.get("vectors") or {}).get("url"),
                                          (e.get("timeIndex") or {}).get("url")]:
                if u:
                    owners.setdefault(u, e["id"])
    out = [finding("dead-link", [owners.get(u, "?")], f"{u}: {why}") for u, why in cg.dead_links(old_urls, **kw)]
    fresh = sorted(set(cg.catalog_urls(candidate)) - set(old_urls))
    out += [finding("candidate-dead-link", [owners.get(u, "?")], f"{u}: {why}") for u, why in cg.dead_links(fresh, **kw)]
    return out


def report(findings, meta):
    lines = [f"# Upkeep check {meta['date']}", ""]
    counts = {g: sum(1 for f in findings if f["group"] == g) for g in GROUPS}
    lines.append(" · ".join(f"{GROUP_TITLES[g]}: {counts[g]}" for g in GROUPS))
    lines.append("")
    if not findings:
        lines += ["Nothing to do: the published catalog is current, every link answers, budgets fit.", ""]
    for g in GROUPS:
        group = [f for f in findings if f["group"] == g]
        if not group:
            continue
        lines += [f"## {GROUP_TITLES[g]}", ""]
        for f in group:
            lines.append(f"- **{f['kind']}**: {f['summary']}  \n  → {f['action']}")
        lines.append("")
    lines += [f"Published catalog generated {meta.get('published')}; {meta.get('links', 0)} links checked."]
    return "\n".join(lines) + "\n"


def check(out_dir, published_path, curated_path, build=cg.build, status=None, sleep=None, today=None):
    out_dir = pathlib.Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    published = json.loads(pathlib.Path(published_path).read_text(encoding="utf-8"))
    curated = json.loads(pathlib.Path(curated_path).read_text(encoding="utf-8"))
    findings, holds, lines = [], [], []
    try:
        candidate = build(curated, report=lines, previous=published, status=status, holds=holds)
    except SystemExit as e:  # catalog_gen stops a build with SystemExit("why")
        candidate = None
        findings.append(finding("build-failed", [], str(e)))
    if candidate is not None:
        (out_dir / "candidate.json").write_text(json.dumps(candidate, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        lines += cg.tier_tables(candidate)  # as `catalog_gen.py build` ends kiwix-report.txt
        (out_dir / "candidate-report.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        findings += compare(published, candidate, holds)
        findings += budget_findings(candidate)
    findings += link_findings(published, candidate or published, status, sleep)
    order = {g: i for i, g in enumerate(GROUPS)}
    findings.sort(key=lambda f: (order[f["group"]], f["kind"], f["ids"]))
    meta = {"date": (today or datetime.date.today()).isoformat(), "published": published.get("generatedAt"),
            "links": len(cg.catalog_urls(published))}
    (out_dir / "findings.json").write_text(json.dumps({**meta, "findings": findings}, indent=1, ensure_ascii=False) + "\n",
                                           encoding="utf-8")
    (out_dir / "report.md").write_text(report(findings, meta), encoding="utf-8")
    return findings


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check")
    c.add_argument("--out", default=str(pathlib.Path.home() / "granger-upkeep" / datetime.date.today().isoformat()))
    c.add_argument("--published", default=str(REPO_ROOT / "catalog/catalog.json"))
    c.add_argument("--curated", default=str(REPO_ROOT / "tools/catalog-gen/curated.json"))
    a = ap.parse_args(argv)
    findings = check(a.out, a.published, a.curated)
    counts = {g: sum(1 for f in findings if f["group"] == g) for g in GROUPS}
    print(f"{a.out}/report.md: " + ", ".join(f"{GROUP_TITLES[g].lower()} {counts[g]}" for g in GROUPS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
