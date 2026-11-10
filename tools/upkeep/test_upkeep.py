import json

import upkeep


def pack(pid, sha, snap="2026-08", zim=None, size=1e9, **extra):
    return {"id": pid, "zimName": zim or pid, "sha256": sha, "snapshotDate": snap, "sizeBytes": size,
            "title": pid.title(), "description": "d", "urls": [f"https://dl.example/{pid}_{snap}.zim"], **extra}


def cat(*packs, **rest):
    return {"formatVersion": 1, "generatedAt": "2026-11-09T00:00:00Z", "packs": list(packs), "models": [], "books": [],
            "presets": [], **rest}


def kinds(findings):
    return sorted((f["kind"], tuple(f["ids"])) for f in findings)


def test_compare_names_moves_holds_additions_removals_and_copy():
    published = cat(pack("cook", "c1"), pack("wikt", "w1"), pack("gone", "g1"),
                    pack("top_nopic", "t1", zim="top", vectors={"url": "v"}), pack("es_mini", "e1"))
    candidate = cat(pack("cook", "c2", "2026-11", size=1.5e9), pack("wikt", "w1"), pack("new", "n1"),
                    pack("top_nopic", "t1", zim="top", vectors={"url": "v"}), dict(pack("es_mini", "e1"), description="D"))
    holds = [{"zim": "wikt", "ids": ["wikt"], "from": "2026-08", "to": "2026-11", "reasons": ["mirror"], "held": True, "text": ""},
             {"zim": "top", "ids": ["top_nopic"], "from": "2026-08", "to": "2026-11", "reasons": ["vectors"], "held": True, "text": ""}]
    found = upkeep.compare(published, candidate, holds)
    assert kinds(found) == [("copy-changed", ("es_mini",)), ("pack-added", ("new",)), ("pack-removed", ("gone",)),
                            ("snapshot-held-mirror", ("wikt",)), ("snapshot-held-vectors", ("top_nopic",)),
                            ("snapshot-ready", ("cook",))]
    ready = next(f for f in found if f["kind"] == "snapshot-ready")
    assert ready["group"] == "ready" and "2026-08 → 2026-11 (+0.50 GB)" in ready["summary"]
    assert next(f for f in found if f["kind"] == "snapshot-held-vectors")["group"] == "needs-neal"


def test_compare_flags_dropped_vectors_forced_moves_and_repo_changes():
    published = cat(pack("xkcd", "x1", timeIndex={"url": "t"}), presets=[{"id": "a", "items": []}])
    candidate = cat(pack("xkcd", "x2", "2026-11"), presets=[{"id": "a", "items": ["b"]}])
    holds = [{"zim": "xkcd", "ids": ["xkcd"], "from": "2026-08", "to": "2026-11", "reasons": ["timeIndex"], "held": False, "text": ""}]
    found = upkeep.compare(published, candidate, holds)
    assert kinds(found) == [("derived-dropped", ("xkcd",)), ("repo-change", ()), ("snapshot-forced", ("xkcd",))]
    assert "presets" in next(f for f in found if f["kind"] == "repo-change")["summary"]


def test_budget_findings_use_catalog_gens_budgets(monkeypatch):
    monkeypatch.setattr(upkeep.cg, "TIER_BUDGETS", {"en": (18e9, 107e9)})
    monkeypatch.setattr(upkeep.cg, "tier_sizes", lambda c, lang: {"All": (17e9, 108e9)})
    [f] = upkeep.budget_findings(cat())
    assert f["kind"] == "budget-over" and "Comprehensive is 108.0 GB, over its 107 GB budget" in f["summary"]


def test_link_findings_split_published_from_candidate():
    published = cat(pack("a", "1"), pack("b", "2"))
    candidate = cat(pack("a", "1"), pack("b", "3", "2026-11"))
    dead = {"https://dl.example/a_2026-08.zim", "https://dl.example/b_2026-11.zim"}
    found = upkeep.link_findings(published, candidate, status=lambda u: 404 if u in dead else 200, sleep=lambda s: None)
    assert kinds(found) == [("candidate-dead-link", ("b",)), ("dead-link", ("a",))]


def test_check_writes_findings_report_and_candidate(tmp_path, monkeypatch):
    monkeypatch.setattr(upkeep.cg, "tier_tables", lambda c: ["", "Readiness tiers in en: ..."])
    published = cat(pack("cook", "c1"))
    (tmp_path / "pub.json").write_text(json.dumps(published))
    (tmp_path / "cur.json").write_text("{}")

    def build(curated, report, previous, status, holds):
        report.append("built")
        return cat(pack("cook", "c2", "2026-11"))

    found = upkeep.check(tmp_path / "out", tmp_path / "pub.json", tmp_path / "cur.json", build=build,
                         status=lambda u: 200, sleep=lambda s: None)
    assert kinds(found) == [("snapshot-ready", ("cook",))]
    data = json.loads((tmp_path / "out/findings.json").read_text())
    assert data["findings"][0]["action"] == "apply-candidate" and data["links"] == 1
    assert json.loads((tmp_path / "out/candidate.json").read_text())["packs"][0]["sha256"] == "c2"
    assert (tmp_path / "out/candidate-report.txt").read_text() == "built\n\nReadiness tiers in en: ...\n"
    md = (tmp_path / "out/report.md").read_text()
    assert "Ready to apply: 1" in md and "**snapshot-ready**: cook: 2026-08 → 2026-11" in md


def test_check_reports_a_failed_build_and_still_checks_links(tmp_path):
    (tmp_path / "pub.json").write_text(json.dumps(cat(pack("cook", "c1"))))
    (tmp_path / "cur.json").write_text("{}")

    def build(*a, **k):
        raise SystemExit("preset starter lists unknown items ['gone']")

    found = upkeep.check(tmp_path / "out", tmp_path / "pub.json", tmp_path / "cur.json", build=build,
                         status=lambda u: 404, sleep=lambda s: None)
    assert kinds(found) == [("build-failed", ()), ("dead-link", ("cook",))]
    assert not (tmp_path / "out/candidate.json").exists()
    assert "unknown items" in (tmp_path / "out/report.md").read_text()


def test_every_kind_has_a_playbook_in_the_readme():
    readme = (upkeep.HERE / "README.md").read_text()
    for kind, (group, action) in upkeep.KINDS.items():
        assert group in upkeep.GROUPS
        assert f"`{kind}`" in readme, kind
        name = action.split(" ")[0]
        if name != "none:":
            assert f"### {name}" in readme, name
