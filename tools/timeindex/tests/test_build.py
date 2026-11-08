import json
import sqlite3
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build  # noqa: E402

SPEC = json.loads((Path(__file__).resolve().parents[1] / "lanes.json").read_text())
# A tiny class tree: battle and empire are roots; "siege" sits under battle; "year in France" under the dropped kind.
PARENTS = {"Q188055": ["Q178561"], "Q99": ["Q18340514"], "Q593644": ["Q901"], "Q1930187": ["Q36180"],
           "Q33231": ["Q1028181"], "Q82955": ["Q4164871"], "Q9143x": ["Q9143"], "Q9143": ["Q34770"]}


def lanes():
    return build.Lanes(SPEC, PARENTS)


def bit(lane):
    return 1 << [l["id"] for l in SPEC["lanes"]].index(lane)


def t(s, prec=11):
    return [s, prec, "Q1985727"]


def test_years_are_astronomical_and_days_kept():
    assert build.astro_year("+1830-10-04T00:00:00Z") == 1830
    assert build.astro_year("-0500-00-00T00:00:00Z") == -499
    assert build.astro_year("-0001-00-00T00:00:00Z") == 0
    assert build.astro_year("-13798000000-00-00T00:00:00Z") == -13797999999
    assert build.month_day("+1066-10-14T00:00:00Z") == 1014


def test_a_person_is_a_lifetime_and_a_living_one_has_no_end():
    kind, start, end, prec = build.dates({"P31": ["Q5"], "P569": [t("+1879-03-14T00:00:00Z")], "P570": [t("+1955-04-18T00:00:00Z")]})
    assert (kind, start, end, prec) == (build.LIFE, (1879, 314), (1955, 418), 11)
    assert build.dates({"P31": ["Q5"], "P569": [t("+1961-08-04T00:00:00Z")]})[2] is None
    assert build.dates({"P31": ["Q5"], "P570": [t("+1500-00-00T00:00:00Z", 9)]}) is None


def test_a_battle_is_a_point_and_an_empire_a_span():
    assert build.dates({"P31": ["Q178561"], "P585": [t("+1066-10-14T00:00:00Z")]}) == (build.POINT, (1066, 1014), None, 11)
    kind, start, end, prec = build.dates({"P31": ["Q48349"], "P571": [t("-0026-00-00T00:00:00Z", 9)], "P576": [t("+0476-00-00T00:00:00Z", 9)]})
    assert (kind, start, end, prec) == (build.SPAN, (-25, 0), (476, 0), 9)
    # Inception alone is a point; publication counts too.
    assert build.dates({"P571": [t("+1830-00-00T00:00:00Z", 9)]})[0] == build.POINT
    assert build.dates({"P577": [t("+1605-00-00T00:00:00Z", 9)]})[1] == (1605, 0)
    assert build.dates({"P31": ["Q11173"]}) is None


def test_lanes_walk_up_and_time_pages_drop():
    l = lanes()
    assert l.of({"P31": ["Q188055"]}) == bit("wars")                     # siege < battle
    assert l.of({"P31": ["Q48349"]}) == bit("civilisations")
    assert l.of({"P31": ["Q99"]}) is None                                 # year in France < events in a year
    assert l.of({"P31": ["Q577"]}) is None
    assert l.of({"P31": ["Q12345"]}) == 0                                 # dated, in no lane: "All" only


def test_people_take_lanes_from_their_first_two_occupations_only():
    l = lanes()
    rec = {"P31": ["Q5"], "P106": ["Q593644", "Q1930187", "Q33231"]}     # chemist, writer, painter
    assert l.of(rec) == bit("people") | bit("science") | bit("art_literature")


def test_programming_languages_are_not_languages():
    l = lanes()
    assert l.of({"P31": ["Q34770"]}) == bit("languages")
    assert l.of({"P31": ["Q9143x"]}) == 0


def test_a_root_whose_label_changed_fails_the_build():
    labels = {q: w for lane in SPEC["lanes"] for q, w in {**lane["roots"], **lane.get("except", {})}.items()}
    labels.update(SPEC["drop"])
    build.Lanes(SPEC, PARENTS, labels)
    labels["Q35120"] = "entity"
    labels["Q901"] = "entity"
    with pytest.raises(ValueError, match="Q901"):
        build.Lanes(SPEC, PARENTS, labels)


def test_build_writes_rows_skips_stubs_and_drops_time_pages(tmp_path):
    from libzim.reader import Archive
    from libzim.writer import Creator, Hint, Item, StringProvider

    class Page(Item):
        def __init__(self, path, title, html):
            super().__init__(); self.p, self.t, self.h = path, title, html
        def get_path(self): return self.p
        def get_title(self): return self.t
        def get_mimetype(self): return "text/html"
        def get_contentprovider(self): return StringProvider(self.h)
        def get_hints(self): return {Hint.FRONT_ARTICLE: True}

    body = "<html><body>" + "x" * 800 + "</body></html>"
    stub = '<html><head><meta http-equiv="refresh" content="0;URL=\'Battle_of_Hastings\'"></head></html>'
    zim = tmp_path / "wikipedia_en_test_maxi_2026-11.zim"
    with Creator(str(zim)).config_indexing(False, "eng") as c:
        c.set_mainpath("Battle_of_Hastings")
        for p, title, html in [("Battle_of_Hastings", "Battle of Hastings", body), ("Roman_Empire", "Roman Empire", body),
                               ("Ada_Lovelace", "Ada Lovelace", body), ("1066_in_England", "1066 in England", body),
                               ("Hastings_battle", "Hastings battle", stub), ("Undated", "Undated", body)]:
            c.add_item(Page(p, title, html))
    items = {
        "Battle_of_Hastings": {"q": "Q83224", "sl": 80, "P31": ["Q178561"], "P585": [t("+1066-10-14T00:00:00Z")], "P625": [50.91, 0.49]},
        "Roman_Empire": {"q": "Q2277", "sl": 250, "P31": ["Q48349"], "P580": [t("-0026-00-00T00:00:00Z", 9)], "P582": [t("+0476-00-00T00:00:00Z", 9)]},
        "Ada_Lovelace": {"q": "Q7259", "sl": 120, "P31": ["Q5"], "P106": ["Q170790"], "P569": [t("+1815-12-10T00:00:00Z")], "P570": [t("+1852-11-27T00:00:00Z")]},
        "1066_in_England": {"q": "Q5", "sl": 3, "P31": ["Q99"], "P585": [t("+1066-00-00T00:00:00Z", 9)]},
        "Hastings_battle": {"q": "Q83224", "sl": 80, "P31": ["Q178561"], "P585": [t("+1066-10-14T00:00:00Z")]},
        "Undated": {"q": "Q1", "sl": 1, "P31": ["Q11173"]},
    }
    out = tmp_path / "out.gtime"
    counts = build.build(build.articles(Archive(zim)), items, lanes(), out, {"zim": zim.name})
    assert counts == {"articles": 5, "matched": 5, "rows": 3, "dropped": 1}
    db = sqlite3.connect(out)
    rows = db.execute("SELECT qid, title, kind, y0, md0, y1, prec, lanes, rank, lat FROM marks ORDER BY y0").fetchall()
    assert rows == [
        (2277, "Roman Empire", build.SPAN, -25, 0, 476, 9, bit("civilisations"), 250, None),
        (83224, "Battle of Hastings", build.POINT, 1066, 1014, None, 11, bit("wars"), 80, 50.91),
        (7259, "Ada Lovelace", build.LIFE, 1815, 1210, 1852, 11, bit("people"), 120, None),
    ]
    meta = dict(db.execute("SELECT key, value FROM meta"))
    assert meta["format"] == "1" and meta["lanes"].split(",")[-1] == "people" and meta["zim"] == zim.name
