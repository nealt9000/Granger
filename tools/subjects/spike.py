"""Plan EX Task 0: subjects for every article of one Wikipedia pack with vectors (the method lives in assign.py).

    python spike.py ITEMS.jsonl CLASSES.jsonl PACK.gvec OUT_DIR [PACK.zim] [LANG]

Writes OUT_DIR/subjects.json ({path: result}) and OUT_DIR/report.json. RULES_ONLY=1 gives what a pack without vectors
gets (rules, dual roots and History only).
"""
import collections
import json
import os
import sys

from assign import Rules, full, load_classes, load_spec, read_items

HERE = os.path.dirname(os.path.abspath(__file__))


def main(items_path, classes_path, gvec_path, out_dir, zim_path=None, lang="en"):
    sys.path.insert(0, os.path.join(HERE, "..", "vectors"))
    from related_spike import G
    os.makedirs(out_dir, exist_ok=True)
    items = read_items(items_path, set(G(gvec_path).keymap()))
    parents, labels = load_classes(classes_path)
    spec, bad = load_spec(labels)
    if bad:
        print("LABEL MISMATCHES (fix subjects.json):\n  " + "\n  ".join(bad))
    rules = Rules(spec, parents, labels)
    if os.environ.get("RULES_ONLY") == "1":
        result = {}
        for t, r in items.items():
            m, subs, mode = rules.rules_only(r)
            if mode != "dropped":
                result[t] = {"q": r["q"], "label": r.get("l"), "main": m, "subjects": subs, "mode": mode}
    else:
        result = full(rules, items, gvec_path, zim_path or None, lang)
    how = collections.Counter(r["mode"] for r in result.values())
    report = {"articles": len(items), "dropped_lists": len(items) - len(result),
              "assigned": sum(1 for r in result.values() if r["main"]), "how": dict(how),
              "subjects_per_article": dict(sorted(collections.Counter(len(r["subjects"]) for r in result.values()).items())),
              "with_history": sum("history" in r["subjects"] for r in result.values()),
              "flagged": sum(r.get("flag", False) for r in result.values()),
              "by_subject": dict(collections.Counter(r["main"] for r in result.values() if r["main"])), "label_mismatches": bad}
    json.dump(result, open(os.path.join(out_dir, "subjects.json"), "w"), ensure_ascii=False)
    json.dump(report, open(os.path.join(out_dir, "report.json"), "w"), indent=1, ensure_ascii=False)
    print(json.dumps(report, indent=1, ensure_ascii=False))
    return result


if __name__ == "__main__":
    main(*sys.argv[1:7])
