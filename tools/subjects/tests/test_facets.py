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


def setup():
    spec, _ = assign.load_spec()
    fs, bad, weak = facets.load_facets(spec)
    return spec, fs, bad, weak


def test_every_root_and_occupation_is_in_exactly_one_facet_with_both_names():
    _, _, bad, _ = setup()
    assert bad == []


def test_a_things_nearest_root_decides_and_a_broad_root_only_when_nothing_else_does():
    spec, fs, _, weak = setup()
    # Rome: a city and a tourist destination; the broad root waits.
    fx = facets.Facets(fs, {"Q_capital": ["Q515"]}, spec, weak)
    assert fx.rule({"q": "Q220", "P31": ["Q_capital", "Q1200957"]}, "places") == "cities"
    assert fx.rule({"q": "Q_x", "P31": ["Q1200957"]}, "places") == "travel"
    assert fx.rule({"q": "Q_y", "P31": ["Q_unknown"]}, "places") is None


def test_a_persons_occupations_vote_and_broad_ones_count_half():
    spec, fs, _, weak = setup()
    fx = facets.Facets(fs, {}, spec, weak)
    mozart = {"q": "Q254", "P31": ["Q5"], "P106": ["Q36834", "Q486748", "Q639669"]}   # composer, pianist, musician
    assert fx.rule(mozart, "music") == "composers"
    biden = {"q": "Q6279", "P31": ["Q5"], "P106": ["Q82955", "Q40348", "Q185351"]}   # politician, lawyer, jurist
    assert fx.rule(biden, "society") == "government"


def test_the_vote_fills_gaps_and_may_move_a_catch_all():
    import numpy as np
    spec, fs, _, weak = setup()
    fx = facets.Facets(fs, {}, spec, weak)
    v = lambda *x: np.array(x, dtype=np.float32) / np.linalg.norm(x)
    vecs = {"Dog": v(1, 0), "Cat": v(0.95, 0.05), "Horse": v(0.9, 0.1), "Lion": v(0.97, 0.03), "Oak": v(0, 1), "Ash": v(0.05, 1)}
    ruled = {"Dog": "animals", "Cat": "animals", "Horse": "animals", "Oak": "plants", "Ash": "plants", "Lion": "living"}
    main = {t: "nature" for t in vecs}
    final = fx.final(ruled, main, {t: False for t in vecs}, vecs)
    assert final["Lion"] == "animals" and final["Oak"] == "plants"
