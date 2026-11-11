import io
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import wdfilter  # noqa: E402


def entity(qid, sitelinks, claims=None):
    return json.dumps({"id": qid, "labels": {"en": {"value": qid.lower()}},
                       "sitelinks": {s: {"site": s, "title": t} for s, t in sitelinks.items()},
                       "claims": claims or {}}, ensure_ascii=False, separators=(",", ":")) + ",\n"


def claim(value, rank="normal"):
    return {"mainsnak": {"snaktype": "value", "datavalue": {"value": value}}, "rank": rank}


def time(t, prec=11):
    return {"time": t, "precision": prec, "calendarmodel": "http://www.wikidata.org/entity/Q1985727"}


def run(lines, titles, site, classes=False):
    items, cls = io.StringIO(), io.StringIO() if classes else None
    wdfilter.run(iter(["[\n", *lines, "]\n"]), set(titles), items, cls, site=site, log=io.StringIO())
    return [json.loads(l) for l in items.getvalue().splitlines()], cls


def test_matches_on_the_chosen_site_and_keys_by_its_path():
    belgium = entity("Q31", {"enwiki": "Belgium", "eswiki": "Bélgica"},
                     {"P31": [claim({"id": "Q6256"})], "P571": [claim(time("+1830-10-04T00:00:00Z"))]})
    other = entity("Q99", {"enwiki": "Elsewhere"})
    es, _ = run([belgium, other], ["Bélgica"], "eswiki")
    assert [r["t"] for r in es] == ["Bélgica"]
    assert es[0]["en"] == "Belgium" and "es" not in es[0]
    assert es[0]["P571"] == [["+1830-10-04T00:00:00Z", 11, "Q1985727"]]
    assert es[0]["sl"] == 2
    en, _ = run([belgium, other], ["Belgium"], "enwiki")
    assert [r["t"] for r in en] == ["Belgium"] and en[0]["es"] == "Bélgica"


def test_spaces_become_underscores_and_escapes_are_read():
    q = entity("Q1", {"eswiki": 'Guerra de los "Cien Años"'})
    out, _ = run([q], ['Guerra_de_los_"Cien_Años"'], "eswiki")
    assert out[0]["t"] == 'Guerra_de_los_"Cien_Años"'


def test_preferred_rank_wins_and_deprecated_is_dropped():
    q = entity("Q2", {"eswiki": "X"}, {"P569": [claim(time("+1900-01-01T00:00:00Z"), "deprecated"),
                                                claim(time("+1901-01-01T00:00:00Z")),
                                                claim(time("+1902-01-01T00:00:00Z"), "preferred")]})
    out, _ = run([q], ["X"], "eswiki")
    assert out[0]["P569"] == [["+1902-01-01T00:00:00Z", 11, "Q1985727"]]


def test_classes_only_when_asked():
    cls_line = entity("Q5", {}, {"P279": [claim({"id": "Q215627"})]})
    _, none = run([cls_line], [], "eswiki")
    assert none is None
    _, cls = run([cls_line], [], "eswiki", classes=True)
    assert json.loads(cls.getvalue()) == {"q": "Q5", "l": "q5", "p279": ["Q215627"]}


def test_link_properties_ride_along():
    q = entity("Q1001", {"enwiki": "Pride_and_Prejudice"},
               {"P50": [claim({"id": "Q36322"})], "P2034": [claim("1342")], "P1899": [claim("123")],
                "P434": [claim("abc-123")], "P86": [claim({"id": "Q255"})]})
    out, _ = run([q], ["Pride_and_Prejudice"], "enwiki")
    r = out[0]
    assert r["P50"] == ["Q36322"] and r["P86"] == ["Q255"]
    assert r["P2034"] == ["1342"] and r["P1899"] == ["123"] and r["P434"] == ["abc-123"]


def test_all_items_and_several_sites_in_one_pass():
    belgium = entity("Q31", {"enwiki": "Belgium", "eswiki": "Bélgica"})
    only_en = entity("Q99", {"enwiki": "Elsewhere"})
    no_wiki = entity("Q7", {"frwiki": "Ailleurs"})
    en, es = io.StringIO(), io.StringIO()
    wdfilter.run_sites(iter(["[\n", belgium, only_en, no_wiki, "]\n"]),
                       {"enwiki": (None, en), "eswiki": (None, es)}, log=io.StringIO())
    assert [json.loads(l)["t"] for l in en.getvalue().splitlines()] == ["Belgium", "Elsewhere"]
    assert [json.loads(l)["t"] for l in es.getvalue().splitlines()] == ["Bélgica"]
