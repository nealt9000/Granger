import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "sidecar"))
import build  # noqa: E402
import meta  # noqa: E402
from zim_articles import articles  # noqa: E402


def make_zim(tmp_path):
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
    stub = '<html><head><meta http-equiv="refresh" content="0;URL=\'Jane_Austen\'"></head></html>'
    zim = tmp_path / "wikipedia_en_test_maxi_2026-11.zim"
    with Creator(str(zim)).config_indexing(False, "eng") as c:
        c.set_mainpath("Jane_Austen")
        for p, title, html in [("Jane_Austen", "Jane Austen", body), ("Pride_and_Prejudice", "Pride and Prejudice", body),
                               ("London", "London", body), ("No_item", "No item", body), ("Austen", "Austen", stub)]:
            c.add_item(Page(p, title, html))
    return zim


ITEMS = [
    {"q": "Q36322", "t": "Jane_Austen", "sl": 150, "es": "Jane Austen", "P31": ["Q5"], "P800": ["Q170583"]},
    {"q": "Q170583", "t": "Pride_and_Prejudice", "sl": 90, "es": "Orgullo y prejuicio", "P50": ["Q36322"], "P2034": ["1342", "42671"],
     "P1899": ["45"]},
    {"q": "Q84", "t": "London", "sl": 300, "P625": [51.50722, -0.1275]},
    {"q": "Q999", "t": "Not_in_the_pack", "sl": 5},
    {"q": "Q36322", "t": "Austen", "sl": 150},   # the stub's path: not a real article, so not indexed
]


def test_build_maps_articles_links_and_coords(tmp_path):
    from libzim.reader import Archive
    zim = make_zim(tmp_path)
    out = tmp_path / "x.gqid"
    counts = build.build([p for p, _ in articles(Archive(zim))], iter(ITEMS), out, zim, meta.sha256(zim))
    assert {k: counts[k] for k in ("articles", "matched", "links", "coords")} == \
        {"articles": 4, "matched": 3, "links": 5, "coords": 1}
    db = sqlite3.connect(out)
    assert db.execute("SELECT path, qid, rank, other FROM articles ORDER BY path").fetchall() == [
        ("Jane_Austen", 36322, 150, "Jane Austen"), ("London", 84, 300, None),
        ("Pride_and_Prejudice", 170583, 90, "Orgullo y prejuicio")]
    assert db.execute("SELECT qid FROM links WHERE prop = 'P2034' AND value = '1342'").fetchall() == [(170583,)]
    assert sorted(db.execute("SELECT prop, value FROM links WHERE qid = 170583").fetchall()) == \
        [("P1899", "45"), ("P2034", "1342"), ("P2034", "42671"), ("P50", "Q36322")]
    assert db.execute("SELECT lat, lon FROM coords WHERE qid = 84").fetchone() == (51.50722, -0.1275)
    db.close()
    assert meta.check(out, zim) == []
    m = meta.read_meta(out)
    assert m["kind"] == "gqid" and m["matched"] == "3" and m["builder"] == "tools/qidindex/build.py"


def test_the_spanish_side_carries_the_english_title():
    (_, _, other), _, _ = build.rows_of({"q": "Q31", "t": "Bélgica", "sl": 383, "en": "Belgium"})
    assert other == "Belgium"


def test_check_passes_a_good_index_and_fails_low_coverage(tmp_path, capsys):
    from libzim.reader import Archive
    import check
    zim = make_zim(tmp_path)
    out = tmp_path / "wikipedia_en_test_maxi_2026-11.gqid"
    build.build([p for p, _ in articles(Archive(zim))], iter(ITEMS), out, zim, meta.sha256(zim))
    assert check.main([str(out), "--source", str(zim), "--min-share", "0.7"]) == 0
    assert "3/4 articles have an item (75.00%)" in capsys.readouterr().out
    assert check.main([str(out), "--min-share", "0.9"]) == 1
    assert "floor 90%" in capsys.readouterr().out


def test_path_of_reads_the_path_without_parsing_and_handles_escapes(tmp_path):
    import json
    lines = [json.dumps(r, ensure_ascii=False) for r in
             [{"q": "Q1", "t": 'Guerra_de_los_"Cien_Años"', "sl": 3}, {"q": "Q2", "t": "C:\\\\", "sl": 1}, {"q": "Q3", "t": "Plain"}]]
    assert [build.path_of(l) for l in lines] == ['Guerra_de_los_"Cien_Años"', "C:\\\\", "Plain"]
    f = tmp_path / "items.jsonl"
    f.write_text("\n".join(lines) + "\n", encoding="utf8")
    assert [r["q"] for r in build.read_items(f, {"Plain", 'Guerra_de_los_"Cien_Años"'})] == ["Q1", "Q3"]


def make_subjects(tmp_path, rows):
    f = tmp_path / "subjects.sqlite"
    db = sqlite3.connect(f)
    db.execute("CREATE TABLE subjects(qid INTEGER, ord INTEGER, subject TEXT, PRIMARY KEY(qid, ord))")
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    db.executemany("INSERT INTO subjects VALUES (?,?,?)", rows)
    db.executemany("INSERT INTO meta VALUES (?,?)", [("subjectsVersion", "1"), ("builtAt", "2026-11-16T00:00:00Z")])
    db.commit(); db.close()
    return f


def test_subjects_are_attached_for_the_index_items_only_and_schema_stays_1(tmp_path):
    from libzim.reader import Archive
    zim = make_zim(tmp_path)
    out = tmp_path / "x.gqid"
    subjects = make_subjects(tmp_path, [(36322, 0, "literature"), (36322, 1, "history"), (170583, 0, "literature"),
                                        (999, 0, "science")])
    counts = build.build([p for p, _ in articles(Archive(zim))], iter(ITEMS), out, zim, meta.sha256(zim), subjects)
    assert counts["subjects"] == 2
    db = sqlite3.connect(out)
    assert db.execute("SELECT qid, ord, subject FROM subjects ORDER BY qid, ord").fetchall() == [
        (36322, 0, "literature"), (36322, 1, "history"), (170583, 0, "literature")]
    db.close()
    m = meta.read_meta(out)
    assert m["schemaVersion"] == "1" and m["builderVersion"] == "3" and m["subjectsVersion"] == "1"
    assert m["withSubjects"] == "2" and m["packSubject"] == ""


def test_a_subject_pack_gives_its_subject_to_items_the_rules_leave_without_one(tmp_path):
    db = sqlite3.connect(tmp_path / "wikipedia_en_chemistry_maxi_2026-10.gqid")
    db.executescript(build.SCHEMA)
    db.executemany("INSERT INTO articles VALUES (?,?,?,?)", [("Niobium", 1046, 9, None), ("Law_of_mass_action", 5, 3, None)])
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execute("INSERT INTO meta VALUES ('sourceZim', 'wikipedia_en_chemistry_maxi_2026-10.zim')")
    n = build.add_subjects(db, make_subjects(tmp_path, [(1046, 0, "science"), (1046, 1, "technology")]))
    assert n == 2
    assert db.execute("SELECT qid, ord, subject FROM subjects ORDER BY qid, ord").fetchall() == [
        (5, 0, "science"), (1046, 0, "science"), (1046, 1, "technology")]
    assert build.pack_subject("wikipedia_es_movies_nopic_2026-10.zim") == "film"
    assert build.pack_subject("wikipedia_en_top_nopic_2026-09-14.zim") is None
    assert build.pack_subject("wikipedia_en_all_maxi_2026-08.zim") is None


def test_facets_are_attached_with_their_list_when_the_subjects_file_has_them(tmp_path):
    db = sqlite3.connect(tmp_path / "wikipedia_en_top_maxi_2026-09.gqid")
    db.executescript(build.SCHEMA)
    db.executemany("INSERT INTO articles VALUES (?,?,?,?)", [("Napoleon", 517, 9, None), ("Paris", 90, 9, None)])
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execute("INSERT INTO meta VALUES ('sourceZim', 'wikipedia_en_top_maxi_2026-09.zim')")
    f = make_subjects(tmp_path, [(517, 0, "history"), (90, 0, "places")])
    s = sqlite3.connect(f)
    s.execute("CREATE TABLE facets(qid INTEGER PRIMARY KEY, facet TEXT NOT NULL)")
    s.executemany("INSERT INTO facets VALUES (?,?)", [(517, "history.rulers"), (999, "science.space")])
    s.execute("INSERT INTO meta VALUES ('facets', '{\"history\":[{\"id\":\"rulers\",\"en\":\"Rulers\",\"es\":\"Gobernantes\"}]}')")
    s.commit(); s.close()
    build.add_subjects(db, f)
    assert db.execute("SELECT facet, qid FROM facets").fetchall() == [(0, 517)]
    assert '"code":0' in db.execute("SELECT value FROM meta WHERE key = 'facets'").fetchone()[0]
    assert "Gobernantes" in db.execute("SELECT value FROM meta WHERE key = 'facets'").fetchone()[0]
    # A subjects file without facets leaves none (older files).
    db2 = sqlite3.connect(tmp_path / "b.gqid")
    db2.executescript(build.SCHEMA)
    db2.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db2.execute("INSERT INTO meta VALUES ('sourceZim', 'x.zim')")
    build.add_subjects(db2, make_subjects_plain(tmp_path))
    assert not db2.execute("SELECT 1 FROM sqlite_master WHERE name = 'facets'").fetchone()


def make_subjects_plain(tmp_path):
    d = tmp_path / "plain"
    d.mkdir()
    return make_subjects(d, [])


def test_an_item_may_sit_in_several_lenses(tmp_path):
    db = sqlite3.connect(tmp_path / "wikipedia_en_top_maxi_2026-09.gqid")
    db.executescript(build.SCHEMA)
    db.executemany("INSERT INTO articles VALUES (?,?,?,?)", [("Dog", 144, 9, None)])
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execute("INSERT INTO meta VALUES ('sourceZim', 'wikipedia_en_top_maxi_2026-09.zim')")
    f = make_subjects(tmp_path, [(144, 0, "nature")])
    s = sqlite3.connect(f)
    s.execute("CREATE TABLE facets(qid INTEGER NOT NULL, facet TEXT NOT NULL, PRIMARY KEY(qid, facet)) WITHOUT ROWID")
    s.executemany("INSERT INTO facets VALUES (?,?)", [(144, "nature.animals"), (144, "nature.farm")])
    s.execute("INSERT INTO meta VALUES ('facets', '{\"nature\":[{\"id\":\"animals\",\"en\":\"Animal kingdom\",\"es\":\"Reino animal\"},"
              "{\"id\":\"farm\",\"en\":\"Pets & farm animals\",\"es\":\"Mascotas\"}]}')")
    s.commit(); s.close()
    build.add_subjects(db, f)
    assert sorted(db.execute("SELECT facet, qid FROM facets").fetchall()) == [(0, 144), (1, 144)]


def make_topics(tmp_path, lang):
    f = tmp_path / f"topics-{lang}.sqlite"
    f.unlink(missing_ok=True)
    t = sqlite3.connect(f)
    t.execute("CREATE TABLE topics(qid INTEGER PRIMARY KEY, under INTEGER NOT NULL, inlinks INTEGER NOT NULL)")
    t.execute("CREATE TABLE topic_parts(topic INTEGER NOT NULL, part INTEGER NOT NULL, ord INTEGER NOT NULL, item INTEGER, "
              "grp INTEGER, text TEXT, note TEXT, PRIMARY KEY(topic, part, ord)) WITHOUT ROWID")
    t.executemany("INSERT INTO topics VALUES (?,?,?)", [(197, 72, 86), (8502, 147, 139)])
    t.executemany("INSERT INTO topic_parts VALUES (?,?,?,?,?,?,?)", [
        (197, 2, 0, 5, -1, None, None), (197, 3, 0, None, None, "History", "History"), (8502, 2, 0, 513, -1, None, None)])
    t.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    t.executemany("INSERT INTO meta VALUES (?,?)", [("lang", lang), ("builtAt", "2026-11-17T00:00:00Z")])
    t.commit(); t.close()
    return f


def test_broad_topics_attach_for_the_index_language_and_its_own_articles(tmp_path):
    db = sqlite3.connect(tmp_path / "wikipedia_en_top_maxi_2026-09.gqid")
    db.executescript(build.SCHEMA)
    db.executemany("INSERT INTO articles VALUES (?,?,?,?)", [("Airplane", 197, 9, None), ("Paris", 90, 9, None)])
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.execute("INSERT INTO meta VALUES ('sourceZim', 'wikipedia_en_top_maxi_2026-09.zim')")
    n = build.add_topics(db, {"en": make_topics(tmp_path, "en"), "es": tmp_path / "missing.sqlite"})
    assert n == 1   # Mountain's article isn't in this index
    assert db.execute("SELECT topic, part, ord, text FROM topic_parts ORDER BY part").fetchall() == [
        (197, 2, 0, None), (197, 3, 0, "History")]
    assert db.execute("SELECT value FROM meta WHERE key = 'withTopics'").fetchone()[0] == "1"
    # No file for its language: no tables.
    es = sqlite3.connect(tmp_path / "wikipedia_es_top_maxi_2026-10.gqid")
    es.executescript(build.SCHEMA)
    es.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    es.execute("INSERT INTO meta VALUES ('sourceZim', 'wikipedia_es_top_maxi_2026-10.zim')")
    assert build.add_topics(es, {"en": make_topics(tmp_path, "en")}) == 0
    assert not es.execute("SELECT 1 FROM sqlite_master WHERE name = 'topic_parts'").fetchone()
