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
