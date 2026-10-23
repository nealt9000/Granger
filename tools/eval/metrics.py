"""Metrics for one run: the pass-bar numbers, retrieval recall, medical flag, Best guess grades and timings.

`compute(items, records, grades)` -> dict. `items` are dataset items, `records` result records, `grades` from grading.load_grades.
Only items that have a record count. A run with media items (group `media` or `media-control`) also gets `media`:
`cited` (a `media` item cited one of its expected media sources) and `intrusion` (a `media-control` item cited any
media passage), with their bars in `pass`; without media items neither appears. `apply_threshold` re-decides outcomes from the recorded `found` scores, for sweeps.
"""
import copy
import math

from dataset import MEDIA_CONTROL, is_media, location_matches
from grading import current_grade, replies

BAR = {"unanswerable": 0.90, "answerable": 0.80, "guessWrongMax": 0.10, "guessGoodMin": 0.60,
       "mediaCited": 0.80, "mediaIntrusionMax": 0}
MEDIA = "media"


def _rate(a, b):
    return a / b if b else None


def matches(passage, answers):
    return any(passage.get("source") == a["source"] and location_matches(a["source"], a["location"], passage.get("location") or "") for a in answers)


def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[max(0, math.ceil(p * len(s)) - 1)]


def median(values):
    if not values:
        return None
    s = sorted(values)
    m = len(s) // 2
    return s[m] if len(s) % 2 else (s[m - 1] + s[m]) / 2


def cited_passages(record):
    """The chosen passages the reply cited (`cited` numbers them from 1)."""
    chosen = record.get("chosen", [])
    return [chosen[n - 1] for n in record.get("cited", []) if isinstance(n, int) and 0 < n <= len(chosen)]


def _is_media_passage(p):
    return p.get("kind") == "MEDIA" or is_media(p.get("source"))


def _media(by_id, recs):
    media = [r for r in recs if by_id[r["id"]].get("group") == MEDIA]
    controls = [r for r in recs if by_id[r["id"]].get("group") == MEDIA_CONTROL]
    if not media and not controls:
        return None
    hit = sum(1 for r in media if any(matches(p, by_id[r["id"]]["answers"]) for p in cited_passages(r)))
    intruded = sum(1 for r in controls if any(_is_media_passage(p) for p in cited_passages(r)))
    return {"cited": {"n": len(media), "hit": hit, "rate": _rate(hit, len(media))},
            "intrusion": {"n": len(controls), "hit": intruded, "rate": _rate(intruded, len(controls))}}


def _timing(records, key):
    v = [r["ms"][key] for r in records if r.get("ms") and key in r["ms"]]
    return {"median": median(v), "p90": percentile(v, 0.9), "n": len(v)}




def compute(items, records, grades):
    by_id = {i["id"]: i for i in items}
    recs = [r for r in records if r["id"] in by_id]
    un = [r for r in recs if not by_id[r["id"]]["answerable"]]
    an = [r for r in recs if by_id[r["id"]]["answerable"]]
    handled = sum(1 for r in un if r["outcome"] == "declined")
    grounded = ungraded = 0
    for r in an:
        if r["outcome"] != "answered":
            continue
        g = current_grade(grades, r["id"], "answer", r.get("reply", ""))
        if g is None:
            ungraded += 1
        elif g["grade"] == "grounded":
            grounded += 1
    out = {
        "n": len(recs),
        "unanswerable": {"n": len(un), "handled": handled, "rate": _rate(handled, len(un))},
        "answerable": {"n": len(an), "grounded": grounded, "ungraded": ungraded, "rate": _rate(grounded, len(an))},
        "outcomes": {},
    }
    for r in recs:
        out["outcomes"][r["outcome"]] = out["outcomes"].get(r["outcome"], 0) + 1

    hit = fnc = nf = 0
    with_answers = [r for r in an if by_id[r["id"]].get("answers")]  # media controls have none
    for r in with_answers:
        ans = by_id[r["id"]]["answers"]
        if any(matches(p, ans) for p in r.get("chosen", [])[:4]):
            hit += 1
        elif any(matches(p, ans) for p in r.get("found", [])):
            fnc += 1
        else:
            nf += 1
    out["retrieval"] = {"n": len(with_answers), "recallAt4": _rate(hit, len(with_answers)), "foundNotChosen": fnc, "neverFound": nf}

    flagged = [(by_id[r["id"]]["medical"], bool(r.get("medical"))) for r in recs]
    med = [pair for pair in flagged if pair[0]]
    out["medicalFlag"] = {"n": len(flagged), "accuracy": _rate(sum(1 for a, b in flagged if a == b), len(flagged)),
                          "recall": _rate(sum(1 for a, b in med if b), len(med)), "medicalItems": len(med)}

    deeper = [r for r in recs if r.get("deeper")]
    dg = sum(1 for r in deeper if (current_grade(grades, r["id"], "deeper", r["deeper"].get("reply", "")) or {}).get("grade") == "grounded"
             and r["deeper"].get("outcome") == "answered")
    out["deeper"] = {"n": len(deeper), "grounded": dg, "rate": _rate(dg, len(deeper))}

    out["guesses"] = {}
    for model in ("small", "large"):
        have = [r for r in recs if model in (r.get("guesses") or {})]
        if not have:
            continue
        counts = {k: 0 for k in ("right", "partly", "wrong", "confidently-wrong")}
        for r in have:
            g = current_grade(grades, r["id"], "guess-" + model, r["guesses"][model].get("text", ""))
            if g:
                counts[g["grade"]] += 1
        graded = sum(counts.values())
        out["guesses"][model] = {
            "n": len(have), "graded": graded, "right": counts["right"], "partly": counts["partly"], "wrong": counts["wrong"],
            "confidentlyWrong": counts["confidently-wrong"],
            "confidentlyWrongRate": _rate(counts["confidently-wrong"], graded),
            "rightOrPartlyRate": _rate(counts["right"] + counts["partly"], graded),
            "ms": median([r["guesses"][model]["ms"] for r in have if "ms" in r["guesses"][model]]),
        }
        s = out["guesses"][model]
        s["goodEnough"] = (graded > 0 and s["confidentlyWrongRate"] <= BAR["guessWrongMax"] and s["rightOrPartlyRate"] >= BAR["guessGoodMin"])

    out["timings"] = {"total": _timing(recs, "total"), "firstWords": _timing(recs, "firstWords")}
    out["pass"] = {
        "unanswerable": out["unanswerable"]["rate"] is not None and out["unanswerable"]["rate"] >= BAR["unanswerable"],
        "answerable": out["answerable"]["rate"] is not None and out["answerable"]["rate"] >= BAR["answerable"],
    }
    media = _media(by_id, recs)
    if media:
        out["media"] = media
        # None when the run has no items of that group (e.g. run with --groups media)
        out["pass"]["mediaCited"] = None if not media["cited"]["n"] else media["cited"]["rate"] >= BAR["mediaCited"]
        out["pass"]["mediaIntrusion"] = None if not media["intrusion"]["n"] else media["intrusion"]["hit"] <= BAR["mediaIntrusionMax"]
    return out


def apply_threshold(records, lexical):
    """Copies of the records where any item whose best `found` lexical score is below `lexical` becomes a decline."""
    out = []
    for r in records:
        r = copy.deepcopy(r)
        scores = [p["lexical"] for p in r.get("found", []) if "lexical" in p]
        if scores and max(scores) < lexical and r["outcome"] != "failed":
            r["outcome"] = "declined"
        out.append(r)
    return out


def sweep(items, records, grades, start, stop, step):
    rows, i = [], 0
    while True:
        t = round(start + i * step, 6)
        if t > stop + 1e-9:
            break
        m = compute(items, apply_threshold(records, t), grades)
        rows.append({"lexical": t, "unanswerableRate": m["unanswerable"]["rate"], "answerableGrounded": m["answerable"]["rate"],
                     "answerableUngraded": m["answerable"]["ungraded"]})
        i += 1
    return rows
