import metrics
from grading import reply_hash


def P(source="s", location="loc", lexical=0.5):
    return {"kind": "ARTICLE", "source": source, "title": "t", "location": location, "heading": "", "lexical": lexical}


def make():
    """10 items. u1..u3 unanswerable; a1..a5 answerable; m1 medical answerable; m2 medical unanswerable."""
    items, recs = [], []

    def add(id, answerable, outcome, medical=False, rec_medical=None, found=(), chosen=(), reply="r", ms=1000, **extra):
        items.append({"id": id, "answerable": answerable, "medical": medical, "question": "q",
                      "answers": [{"source": "s", "location": "Paris", "quote": "x"}] if answerable else []})
        recs.append({"id": id, "medical": medical if rec_medical is None else rec_medical, "outcome": outcome, "reply": reply,
                     "found": list(found), "chosen": list(chosen), "ms": {"total": ms, "firstWords": ms // 2}, **extra})

    add("u1", False, "declined", found=[P(lexical=0.30)])
    add("u2", False, "answered", found=[P(lexical=0.60)])
    add("u3", False, "unverified", found=[P(lexical=0.45)])
    add("a1", True, "answered", found=[P(location="Paris")], chosen=[P(location="Paris")], ms=2000)
    add("a2", True, "answered", found=[P(location="Paris")], chosen=[P(location="Lyon")] * 4 + [P(location="Paris")], ms=3000)
    add("a3", True, "declined", found=[P(location="Lyon")], chosen=[], ms=4000)
    add("a4", True, "answered", found=[P(location="Paris")], chosen=[P(location="Paris")], ms=5000)
    add("a5", True, "declined", found=[], chosen=[], ms=20000)
    add("m1", True, "answered", medical=True, found=[P(location="Paris")], chosen=[P(location="Paris")], ms=1000)
    add("m2", False, "declined", medical=True, rec_medical=False, found=[P(lexical=0.2)])
    return items, recs


def grades(**by_id):
    g = {}
    for k, (kind, grade, reply) in by_id.items():
        g[(k, kind)] = {"id": k, "kind": kind, "grade": grade, "replyHash": reply_hash(reply), "reason": ""}
    return g


def test_unanswerable_handled_counts_only_declines():
    items, recs = make()
    m = metrics.compute(items, recs, {})
    assert m["unanswerable"] == {"n": 4, "handled": 2, "rate": 0.5}


def test_grounded_needs_answered_and_grounded_grade():
    items, recs = make()
    g = grades(a1=("answer", "grounded", "r"), a2=("answer", "not-grounded", "r"), a3=("answer", "grounded", "r"), m1=("answer", "grounded", "r"))
    m = metrics.compute(items, recs, g)
    assert m["answerable"]["n"] == 6 and m["answerable"]["grounded"] == 2  # a1 and m1; a3 declined; a4 ungraded
    assert m["answerable"]["ungraded"] == 1  # a4 answered but not yet graded


def test_recall_at_4_matches_source_and_location():
    items, recs = make()
    m = metrics.compute(items, recs, {})
    r = m["retrieval"]
    assert r["recallAt4"] == 3 / 6  # a1, a4, m1 (a2 has Paris only 5th)


def test_found_but_not_chosen_is_split_from_never_found():
    items, recs = make()
    r = metrics.compute(items, recs, {})["retrieval"]
    assert r["foundNotChosen"] == 1  # a2: found+chosen 5th but not in top 4
    assert r["neverFound"] == 2  # a3 (only Lyon), a5


def test_medical_flag_accuracy_and_recall():
    items, recs = make()
    m = metrics.compute(items, recs, {})["medicalFlag"]
    assert m["accuracy"] == 9 / 10
    assert m["recall"] == 1 / 2  # m1 flagged; m2 missed
    items2 = [dict(i) for i in items]
    recs2 = [dict(r) for r in recs]
    recs2[-1]["medical"] = True
    assert metrics.compute(items2, recs2, {})["medicalFlag"]["recall"] == 1.0


def test_guess_grades_per_model():
    items, recs = make()
    recs[0]["guesses"] = {"small": {"text": "s1", "ms": 1, "fellBack": False}, "large": {"text": "l1", "ms": 1, "fellBack": False}}
    recs[1]["guesses"] = {"small": {"text": "s2", "ms": 1, "fellBack": False}}
    recs[2]["guesses"] = {"small": {"text": "s3", "ms": 1, "fellBack": False}}
    g = grades(u1=("guess-small", "right", "s1"), u2=("guess-small", "confidently-wrong", "s2"))
    g.update(grades(u1=("guess-large", "partly", "l1")))
    gm = metrics.compute(items, recs, g)["guesses"]
    assert gm["small"]["n"] == 3 and gm["small"]["graded"] == 2
    assert gm["small"]["right"] == 1 and gm["small"]["confidentlyWrong"] == 1
    assert gm["small"]["confidentlyWrongRate"] == 0.5 and gm["small"]["rightOrPartlyRate"] == 0.5
    assert gm["large"]["partly"] == 1 and gm["large"]["rightOrPartlyRate"] == 1.0


def test_timings_median_and_p90():
    items, recs = make()
    t = metrics.compute(items, recs, {})["timings"]["total"]
    # sorted: 1000 x5, 2000, 3000, 4000, 5000, 20000
    assert t["median"] == 1500
    assert t["p90"] == 5000


def test_sweep_moves_declines_with_threshold():
    items, recs = make()
    low = metrics.compute(items, metrics.apply_threshold(recs, 0.20), {})
    high = metrics.compute(items, metrics.apply_threshold(recs, 0.65), {})
    assert low["unanswerable"]["handled"] == 2
    assert high["unanswerable"]["handled"] == 4  # u2 (0.60) and u3 (0.45) now decline
    assert metrics.apply_threshold(recs, 0.65)[1]["outcome"] == "declined"
    assert metrics.apply_threshold(recs, 0.20)[1]["outcome"] == "answered"
