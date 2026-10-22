"""Task 7's rankers: keyword and dense passages merged, deduplicated by text, and gated."""
import merge
from replay import Config

CFG = Config()


def P(text, title="Untitled", kind="ARTICLE", source="wikipedia_en_top", location="", cosine=None):
    p = {"kind": kind, "source": source, "title": title, "location": location or title, "heading": "", "text": text, "searchScore": 0.0}
    if cosine is not None:
        p["cosine"] = cosine
    return p


def corpus(passages, keywords):
    return {"id": "q", "keywords": keywords, "passages": passages}


def texts(ps):
    return [p["text"] for p in ps]


def test_a_dense_hit_with_a_keyword_twin_is_one_passage():
    lexical = P("Rust is an iron oxide formed by oxidation.", title="Rust")
    dense = P("Rust is an iron oxide  formed by oxidation.", title="Rust", cosine=0.82)
    out = merge.hybrid(0.1, 0.74)("what is rust", corpus([lexical], ["rust"]), CFG, [dense])
    assert texts(out) == ["Rust is an iron oxide formed by oxidation."]


def test_dense_hits_below_the_floor_are_left_out():
    hits = [P("Moon distance is 384,400 km.", title="Moon", cosine=0.80), P("A poem about the night.", title="Night", cosine=0.70)]
    out = merge.dense_only(0.74)("how far away is the moon", corpus([], ["moon"]), CFG, hits)
    assert texts(out) == ["Moon distance is 384,400 km."]


def test_the_gate_empties_a_question_with_weak_dense_and_partial_keyword_matches():
    lexical = P("The bank of the river was steep.", title="River")
    hits = [P("Banks hold deposits.", title="Bank", cosine=0.75)]
    ranker = merge.gated(merge.hybrid(0.1, 0.74), 0.78, 1.0)
    assert ranker("what is my bank balance", corpus([lexical], ["bank", "balance"]), CFG, hits) == []


def test_the_gate_passes_a_strong_dense_hit():
    hits = [P("Paris is the capital of France.", title="Paris", cosine=0.85)]
    ranker = merge.gated(merge.hybrid(0.1, 0.74), 0.78, 1.0)
    assert texts(ranker("capital of france", corpus([], ["capital", "france"]), CFG, hits)) == ["Paris is the capital of France."]


def test_the_gate_passes_a_complete_keyword_match():
    lexical = P("Paris is the capital of France.", title="Paris")
    ranker = merge.gated(merge.hybrid(0.1, 0.74), 0.78, 1.0)
    assert texts(ranker("capital of france", corpus([lexical], ["capital", "france"]), CFG, [])) == ["Paris is the capital of France."]


def test_slots_reserve_places_for_dense_hits_keyword_ranking_missed():
    lexical = [P("steam %d engine" % i, title="Steam %d" % i) for i in range(4)]
    hits = [P("Boilers raise steam pressure.", title="Boiler", cosine=0.80)]
    out = merge.slots(1, 0.74)("how does a steam engine work", corpus(lexical, ["steam", "engine"]), CFG, hits)
    assert len(out) == 4 and "Boilers raise steam pressure." in texts(out)
