import json

import answerers


def rec(id, stages, outcome="answered", reply="old [1]"):
    return {"id": id, "outcome": outcome, "reply": reply, "chosen": [{"source": "s"}, {"source": "t"}],
            "prompts": [{"stage": s, "prompt": "%s %s" % (s, id)} for s in stages],
            "ms": {"rewrite": 100, "search": 200, "rank": 5, "firstWords": 9000}, "deeper": {"outcome": "answered"}}


def test_prompts_and_build(tmp_path, monkeypatch):
    monkeypatch.setattr(answerers, "RUNS", tmp_path)
    (tmp_path / "base").mkdir(); (tmp_path / "new").mkdir()
    (tmp_path / "base" / "dataset.json").write_text("[]")
    answerers.write_jsonl(tmp_path / "base" / "results.jsonl", [
        rec("a", ["rewrite", "answer"]),
        rec("f", ["prior-rewrite", "prior-answer", "rewrite", "answer"]),
        rec("e", ["rewrite"], outcome="declined", reply=""),
    ])
    assert answerers.prompts("base") == [{"id": "a", "prompt": "answer a", "passages": 2},
                                         {"id": "f", "prompt": "answer f", "passages": 2}]
    answerers.write_jsonl(tmp_path / "new" / "replay.jsonl", [
        {"id": "a", "reply": "NOT IN LIBRARY", "outcome": "declined", "cited": [], "firstWordsMs": 1000, "totalMs": 3000,
         "peakRssMb": 2000, "loadMs": 5000, "raw": None},
        {"id": "f", "reply": "new [2]", "outcome": "answered", "cited": [2], "firstWordsMs": 700, "totalMs": 2000, "peakRssMb": 2100},
    ])
    out = {r["id"]: r for r in answerers.build("base", "new")}
    assert (out["a"]["outcome"], out["a"]["reply"], out["a"]["ms"]["firstWords"]) == ("declined", "NOT IN LIBRARY", 1305)
    assert out["a"]["replay"]["loadMs"] == 5000 and "raw" not in out["a"]["replay"]
    assert (out["f"]["cited"], "deeper" in out["f"]) == ("[2]", False)
    assert out["e"]["outcome"] == "declined"  # never reached the model: kept as it was


def test_build_keeps_the_r4_fields_when_present(tmp_path, monkeypatch):
    monkeypatch.setattr(answerers, "RUNS", tmp_path)
    (tmp_path / "base").mkdir(); (tmp_path / "new").mkdir()
    (tmp_path / "base" / "dataset.json").write_text("[]")
    answerers.write_jsonl(tmp_path / "base" / "results.jsonl", [rec("a", ["rewrite", "answer"]), rec("b", ["rewrite", "answer"])])
    answerers.write_jsonl(tmp_path / "new" / "replay.jsonl", [
        {"id": "a", "reply": "x [1]", "outcome": "answered", "cited": [1], "firstWordsMs": 900, "totalMs": 4000, "peakRssMb": 1,
         "loadMs": 6000, "memBeforeLoad": {"totalPssMb": 300}, "memAfterLoad": {"totalPssMb": 2900},
         "prefillTokens": 1300, "prefillTokensPerSecond": 400.0, "outputTokens": 40, "decodeTokensPerSecond": 12.5},
        {"id": "b", "reply": "y [2]", "outcome": "answered", "cited": [2], "firstWordsMs": 800, "totalMs": 3000, "peakRssMb": 1},
    ])
    out = {r["id"]: r for r in answerers.build("base", "new")}
    assert out["a"]["replay"]["decodeTokensPerSecond"] == 12.5 and out["a"]["replay"]["memAfterLoad"] == {"totalPssMb": 2900}
    assert "outputTokens" not in out["b"]["replay"]  # an older replay still builds


def test_model_timings():
    replayed = [{"firstWordsMs": ms, "decodeTokensPerSecond": tps, "outputTokens": 40}
                for ms, tps in ((1000, 10.0), (2000, 12.0), (3000, 8.0), (4000, 11.0), (9000, 2.0))]
    t = answerers.model_timings(replayed)
    assert (t["modelFirstWordsMedianMs"], t["modelFirstWordsP90Ms"]) == (3000, 9000)
    assert (t["decodeTokPerSecMedian"], t["decodeTokPerSecP90"], t["outputTokensMedian"]) == (10.0, 2.0, 40)
    old = answerers.model_timings([{"firstWordsMs": 1000}])
    assert old["decodeTokPerSecMedian"] is None and old["modelFirstWordsMedianMs"] == 1000


def test_run_passes_the_r4_extras():
    import argparse
    a = argparse.Namespace(speculative="on", thinking="template", max_tokens=256)
    assert answerers.replay_extras(a) == ["--es", "speculative", "on", "--es", "thinking", "template", "--ei", "maxTokens", "256"]
    assert answerers.replay_extras(argparse.Namespace(speculative=None, thinking=None, max_tokens=None)) == []


def test_prompts_by_ids_and_stage(tmp_path, monkeypatch):
    monkeypatch.setattr(answerers, "RUNS", tmp_path)
    (tmp_path / "base").mkdir()
    answerers.write_jsonl(tmp_path / "base" / "results.jsonl", [
        rec("a", ["rewrite", "answer"]), rec("f", ["prior-rewrite", "prior-answer", "rewrite", "answer"]), rec("e", ["rewrite"])])
    assert [r["id"] for r in answerers.prompts("base", ids=["f"])] == ["f"]
    assert answerers.prompts("base", ids=["e", "f"], stage="rewrite") == [
        {"id": "f", "prompt": "rewrite f", "passages": 0}, {"id": "e", "prompt": "rewrite e", "passages": 0}]
    try:
        answerers.prompts("base", ids=["e"])  # e never reached the answer model
        assert False
    except SystemExit as ex:
        assert "e" in str(ex)


def test_the_committed_sets_are_well_formed():
    for path in sorted(answerers.SETS.glob("*.json")):
        s = json.loads(path.read_text(encoding="utf-8"))
        assert s["stage"] in ("answer", "rewrite") and s["base"] and s["ids"], path
        assert len(set(s["ids"])) == len(s["ids"]), path
    assert answerers.load_set("sample-40", "r1-dense-1")["stage"] == "answer"
    try:
        answerers.load_set("sample-40", "r0-base")
        assert False
    except SystemExit:
        pass
