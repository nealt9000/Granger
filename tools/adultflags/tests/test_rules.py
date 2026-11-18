import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import flags_build as build  # noqa: E402
import rules  # noqa: E402

# A tiny class tree: sexual act under human sexual behaviour, erotic thriller beside pornographic film,
# human penis under sex organ, massacre on its own, wartime sexual violence under human sexual behaviour.
CHILDREN = {
    "Q608": ["Qact", "Qwsv"], "Q185529": ["Qhardsub"], "Q4620674": ["Qpenis"], "Q12198": ["Qwart", "Qhiv"],
}
R = rules.Rules(CHILDREN)


def c(**it):
    it.setdefault("q", "Qx"); it.setdefault("t", "X")
    return R.classify(it)


def test_concept_is_topic_event_is_picture():
    assert c(q="Qact")[0] == rules.TOPIC
    assert c(q="Qmylai", P31=["Qwsv"])[0] == rules.PICTURE
    assert c(q="Q713540", P279=["Q608"])[0] == rules.PICTURE  # comfort women by hand


def test_genre_counts_only_when_hard():
    assert c(P136=["Qhardsub"])[0] == rules.TOPIC
    assert c(P136=["Qeroticthriller"]) is None
    assert c(P136=["Q40446"])[0] == rules.PICTURE  # an artwork in the nude genre


def test_people_by_occupation_only():
    assert c(P31=["Q5"], P106=["Q488111"])[0] == rules.TOPIC
    assert c(P31=["Q5"], P136=["Q185529"]) is None
    assert c(P31=["Q5"], P136=["Q40446"]) is None


def test_outside_review_only_sexual_anatomy():
    assert rules.picture_outside_review("Q4620674", "Penis")
    assert rules.picture_outside_review("Q12198", "Genital_wart")
    assert not rules.picture_outside_review("Q12198", "HIV/AIDS")
    assert not rules.picture_outside_review("Q3199915", "Some_massacre")


def row(lang, q, kind, seed, top, t="X"):
    return {"lang": lang, "q": q, "t": t, "kind": kind, "seed": seed, "top": top}


def test_decide_review_and_rules():
    rows = [
        row("en", "Q1", rules.TOPIC, "Q608", True), row("es", "Q1", rules.TOPIC, "Q608", True),     # unticked in en
        row("en", "Q2", rules.TOPIC, "Q291", True),                                                 # kept
        row("en", "Q3", rules.PICTURE, "Q4620674", True), row("es", "Q3", rules.PICTURE, "Q4620674", True),
        row("en", "Q4", rules.PICTURE, "Q3199915", False),                                          # gore, not Top
        row("en", "Q5", rules.PICTURE, "Q4620674", False),                                          # anatomy, not Top
        row("es", "Q6", rules.TOPIC, "Q8394", False),                                               # rule, not Top
    ]
    out = build.decide(rows, {"en:Q1", "es:Q3"})
    assert (("en", 1) not in out) and (("es", 1) not in out)
    assert out[("en", 2)] == out[("es", 2)] == rules.TOPIC  # a topic applies in every language
    assert out[("en", 3)] == rules.PICTURE and ("es", 3) not in out  # pictures stay per language
    assert ("en", 4) not in out
    assert out[("en", 5)] == rules.PICTURE
    assert out[("en", 6)] == out[("es", 6)] == rules.TOPIC
