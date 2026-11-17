import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
_a = importlib.util.spec_from_file_location("subjects_assign", HERE / "assign.py")
assign = importlib.util.module_from_spec(_a)
_a.loader.exec_module(assign)
_f = importlib.util.spec_from_file_location("subjects_facets", HERE / "facets.py")
facets = importlib.util.module_from_spec(_f)
_f.loader.exec_module(facets)
_l = importlib.util.spec_from_file_location("subjects_lenses", HERE / "lenses.py")
lenses = importlib.util.module_from_spec(_l)
_l.loader.exec_module(lenses)


def setup(parents=None):
    spec, _ = assign.load_spec()
    fs, _, _ = facets.load_facets(spec)
    ls, bad = lenses.load_lenses(spec, facet_spec=fs)
    return spec, ls, bad, lenses.Lenses(ls, parents or {}, spec)


def test_the_list_loads_clean_with_both_names_for_every_subject():
    spec, ls, bad, _ = setup()
    assert bad == []
    assert set(ls) == {s["id"] for s in spec["subjects"]}
    assert sum(len(v) for v in ls.values()) == 108
    assert all(l["en"] and l["es"] for v in ls.values() for l in v)


def test_an_article_sits_in_several_lenses_and_a_lens_draws_across_subjects():
    # Dog: its sub-category seeds Animal kingdom; its class (domesticated animal) puts it in Pets & farm animals too.
    _, _, _, lx = setup({"Q144": ["Q622852"]})
    dog = {"q": "Q144", "P31": [], "P279": ["Q622852"]}
    got = lx.match(dog, ["nature"], "nature.animals")
    assert "nature.animals" in got and "nature.farm" in got
    # A forest (main subject places) joins nature's Ecosystems & habitats, which draws from places.
    forest = {"q": "Q_f", "P31": ["Q4421"]}
    assert "nature.habitats" in lx.match(forest, ["places"], None)


def test_occupations_match_exactly_and_only_for_the_lenses_subjects():
    # politician's parents must not carry a person into Rulers or Crime (the prototype's noise).
    _, _, _, lx = setup({"Q82955": ["Q1097498"]})   # politician -> ruler, if walked
    bush = {"q": "Q207", "P31": ["Q5"], "P106": ["Q82955"]}
    got = lx.match(bush, ["society", "history"], None)
    assert "history.rulers" not in got and "society.leaders" in got
    # A philosopher counts for Philosophy only when Religion & philosophy is the main subject.
    einstein = {"q": "Q937", "P31": ["Q5"], "P106": ["Q4964182"]}
    assert "religion.philosophy" not in lx.match(einstein, ["science", "religion"], None)
    assert "religion.philosophy" in lx.match({**einstein, "q": "Q9312"}, ["religion"], None)


def test_classical_music_needs_a_classical_genre_or_an_early_birth():
    _, _, _, lx = setup()
    composer = "Q36834"
    mozart = {"q": "Q254", "P31": ["Q5"], "P106": [composer], "P569": [["+1756-01-27T00:00:00Z", 11]]}
    pop = {"q": "Q2831", "P31": ["Q5"], "P106": [composer], "P569": [["+1958-08-29T00:00:00Z", 11]], "P136": ["Q37073"]}
    assert "music.classical" in lx.match(mozart, ["music"], None)
    assert "music.classical" not in lx.match(pop, ["music"], None)


def test_time_lenses_take_dated_things_of_their_subjects():
    _, _, _, lx = setup()
    battle = {"q": "Q_b", "P31": ["Q178561"], "P585": [["+1346-08-26T00:00:00Z", 11]]}
    ww2 = {"q": "Q362", "P31": ["Q103495"], "P580": [["+1939-09-01T00:00:00Z", 11]]}
    console = {"q": "Q_s", "P31": ["Q8076"], "P571": [["+1990-11-21T00:00:00Z", 11]]}
    assert "history.medieval" in lx.match(battle, ["history"], None)
    assert "history.modern" in lx.match(ww2, ["history"], None)
    assert "history.modern" not in lx.match(console, ["technology", "history"], None)   # main subject not History
    assert "history.modern" not in lx.match({"q": "Q_u", "P31": ["Q103495"]}, ["history"], None)   # undated


def test_a_problem_in_the_list_is_named():
    spec, _ = assign.load_spec()
    import json
    import tempfile
    bad = {"subjects": {"nature": [{"id": "x", "en": "X", "any": [{"seeds": ["nature.nope"], "colour": 1}]}]}}
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(bad, f)
    fs, _, _ = facets.load_facets(spec)
    _, problems = lenses.load_lenses(spec, path=f.name, facet_spec=fs)
    text = "\n".join(problems)
    assert "needs en and es" in text and "unknown key colour" in text and "seed nature.nope" in text
    assert "history: no lenses" in text
