"""Plan SQ: Ask's recorded retrieval (a run's found.jsonl + dense hits), ranked as Ask ranks, scored at article level as search_score does.

    python3 tools/eval/ask_articles.py tools/eval runs/dev-base-1
"""
import sys
from dataclasses import replace
sys.path.insert(0, sys.argv[1])
import merge, replay
import search_score as ss

items, corpus, dense = merge.load(sys.argv[2])
cfg = replace(replay.Config(), small=40, large=40, preselect=40)
rankers = {
    "keyword only": merge.keyword_only(),
    "hybrid (no gate)": merge.hybrid(0.1, 0.74),
    "app (gated hybrid)": merge.gated(merge.hybrid(0.1, 0.74), 0.78, 1.0),
}
for name, r in rankers.items():
    at = {5: 0, 10: 0}; pat = {5: 0}; n = 0
    for id, it in items.items():
        if not it.get("answerable", True) or not it.get("answers"):
            continue
        n += 1
        ps = r(it["question"], corpus[id], cfg, dense.get(id, []))
        arts, seen = [], []
        for p in ps:
            k = (p["source"], p.get("location") or "")
            if k not in seen:
                seen.append(k); arts.append(p)
        rk = next((i for i, p in enumerate(arts, 1) if merge.matches(p, it["answers"])), None)
        for k in at:
            at[k] += bool(rk and rk <= k)
    print(f"{name:20} articles @5 {at[5]}/{n} ({100*at[5]//n}%)  @10 {at[10]}/{n} ({100*at[10]//n}%)")
