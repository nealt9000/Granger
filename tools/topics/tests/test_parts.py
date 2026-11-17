import collections
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
_p = importlib.util.spec_from_file_location("topics_parts", HERE / "parts.py")
parts = importlib.util.module_from_spec(_p)
_p.loader.exec_module(parts)

HTML = """<div role="note" class="hatnote navigation-not-searchable">For the film, see <a href="Airplane!">Airplane!</a>.</div>
<p>An <a href="Fixed-wing_aircraft">airplane</a> flies.</p>
<h2 id="History">History</h2><p><a href="Wright_brothers">Wrights</a> and <a href="./_assets_/x.css">css</a></p>
<figure><img src="./_assets_/a/Flyer.jpg" decoding="async" data-file-width="640" data-file-height="400"><figcaption>The <a href="Wright_Flyer">Flyer</a>, 1903</figcaption></figure>
<h2 id="Aviaci.C3.B3n"><span id="x" typeof="mw:FallbackId"></span>Aviaci&oacute;n</h2><p><a href="Boeing_747">747</a></p>
<h2 id="See_also">See also</h2><p><a href="Helicopter">Helicopter</a></p>"""


def test_an_article_reads_as_sections_with_links_and_captioned_figures_without_hatnotes_or_back_matter():
    secs = parts.read_article(HTML, "en")
    assert [(s[0], s[1]) for s in secs] == [("", ""), ("History", "History"), ("Aviaci.C3.B3n", "Aviación")]
    assert secs[0][2] == ["Fixed-wing_aircraft"]          # the hatnote's film link is gone
    assert secs[1][2] == ["Wright_brothers"]                # a caption's links are incidental, not the section's
    assert secs[1][3] == [("_assets_/a/Flyer.jpg", 640, 400, "The Flyer, 1903")]
    assert all("Helicopter" not in s[2] for s in secs)     # See also is back matter


def test_people_places_and_dated_things_are_not_broad_but_a_class_with_dates_is():
    assert not parts.is_candidate({"q": "Q1", "P31": ["Q5"]})
    assert not parts.is_candidate({"q": "Q90", "P625": [48.8, 2.3]})
    assert not parts.is_candidate({"q": "Q362", "P31": ["Q103495"], "P580": [["+1939"]]})
    assert parts.is_candidate({"q": "Q144", "P279": ["Q57814795"], "P580": [["-34000"]]})   # Dog: domesticated long ago
    assert parts.is_candidate({"q": "Q197", "P279": ["Q11436"]})


def test_kinds_group_by_the_classes_under_the_topic_that_say_something():
    topic = "T"
    # Classes: F (fighter) and L (airliner) under T; P (powered) under T but covering nearly all; A above T.
    reach = {"F": {"T"}, "L": {"T"}, "P": {"T"}, "T": {"A"}}
    kinds = [f"k{i}" for i in range(10)]
    for i, k in enumerate(kinds):
        reach[k] = {"T", "A", "P"} | ({"F"} if i < 5 else {"L"} if i < 9 else set())
    reach["k9"].discard("P")
    names = {"F": "Fighter aircraft", "L": "Airliner", "P": "Powered aircraft", "A": "Aircraft"}
    rank = {k: 10 - i for i, k in enumerate(kinds)}
    got, groups = parts.kinds_and_groups(topic, kinds, reach, names, rank)
    assert got == kinds
    assert [(g[1], len(g[2])) for g in groups] == [("Fighter aircraft", 5), ("Airliner", 4)]   # not Powered (90%), not Aircraft (above)
    # A class without a name (a Spanish pack without its article) never groups.
    _, groups = parts.kinds_and_groups(topic, kinds, reach, {"L": "Avión comercial"}, rank)
    assert [g[1] for g in groups] == ["Avión comercial"]


def test_people_are_the_neighbours_with_the_neighbourhoods_most_characteristic_occupations():
    pilot, inventor, actor = "Qpilot", "Qinv", "Qact"
    near = [f"p{i}" for i in range(60)]
    occ = {}
    for i, q in enumerate(near):
        occ[q] = [pilot] if i % 6 == 0 else [inventor] if i % 3 == 0 else [actor]
    # Everyone: pilots rare (10 of 10,000), inventors fairly rare (200), actors common (4,000).
    counts = collections.Counter({pilot: 10, inventor: 200, actor: 4000})
    chosen, kept = parts.characteristic_people(near, occ, counts, 10_000)
    assert kept == {pilot}                      # inventors pass LIFT but fall far short of pilots
    assert chosen == [q for q in near if occ[q] == [pilot]][:parts.PEOPLE_MAX]
