import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import link  # noqa: E402
from kb import cls_of, norm  # noqa: E402
import rules as ll_select  # noqa: E402


def kb(tmp_path):
    p = tmp_path / "kb.sqlite"
    db = sqlite3.connect(p)
    db.execute("CREATE TABLE item(qid INTEGER PRIMARY KEY, path TEXT, sl INTEGER, label TEXT, cls TEXT, year INTEGER)")
    db.execute("CREATE TABLE alias(norm TEXT NOT NULL, qid INTEGER NOT NULL, kind TEXT NOT NULL, raw TEXT NOT NULL)")
    items = [(517, "Napoleon", 288, "Napoleon", "human", 1769), (649, "Moscow", 338, "Moscow", "place", 1147),
             (1, "Wickham", 40, "Wickham", "place", None), (2, "Elizabeth_(given_name)", 30, "Elizabeth", "name", None),
             (3, "Fitzwilliam_Darcy", 25, "Mr. Darcy", "fictional", None),
             (192, "Mikhail_Kutuzov", 75, "Mikhail Kutuzov", "human", 1745),
             (7, "Maria_Höfl-Riesch", 37, "Maria Höfl-Riesch", "human", 1984),
             (9391, "Ludwig_Wittgenstein", 174, "Ludwig Wittgenstein", "human", 1889),
             (8, "Mörön", 50, "Mörön", "place", None), (9, "Rostov-on-Don", 120, "Rostov-on-Don", "place", 1749),
             (71, "Ginebra", 200, "Geneva", "place", None), (760, "Santa_Lucía", 236, "Saint Lucia", "place", 1979),
             (183240, "Santa_Lucía_de_Siracusa", 72, "Saint Lucy", "human", 283),
             (204843, "Teodora", 64, "Theodora", "human", 500)]
    items += [(1000 + i, f"Teodora_X{i}", 5, f"Teodora X{i}", "human", None) for i in range(30)]
    db.executemany("INSERT INTO item VALUES (?,?,?,?,?,?)", items)
    db.executemany("INSERT INTO alias(norm, qid, kind, raw) SELECT column1, column2, column3, column1 FROM (VALUES (?,?,?))", [
        ("napoleon", 517, "title"), ("napoleon bonaparte", 517, "redirect"), ("moscow", 649, "title"),
        ("wickham", 1, "title"), ("elizabeth", 2, "redirect"), ("darcy", 3, "redirect"),
        ("kutuzov", 192, "redirect"), ("mikhail kutuzov", 192, "title"), ("maria", 7, "redirect"),
        ("wittgenstein", 9391, "redirect"), ("rostov", 9, "redirect"), ("ginebra", 71, "title"),
        ("teodora", 204843, "title")])
    db.executemany("INSERT INTO alias VALUES ('santa lucia', ?, ?, 'Santa Lucía')", [(760, "title"), (183240, "redirect")])
    db.execute("INSERT INTO alias VALUES ('moron', 8, 'title', 'Mörön')")
    db.commit(); db.close()
    return link.KB(p)


def test_norm_and_classes():
    assert norm("The Emperor’s_Guard") == "emperor s guard"
    assert norm("Kutúzov") == "kutuzov"
    assert cls_of(["Q5"]) == "human" and cls_of(["Q4167410", "Q5"]) == "disambig" and cls_of(["Q95074"]) == "fictional"


def test_resolve_keeps_real_people_and_places_and_drops_the_cast(tmp_path):
    k = kb(tmp_path)
    assert link.resolve(k, "Napoleon’s", "PERSON", "en")[0]["qid"] == 517
    assert link.resolve(k, "General Kutúzov", "PERSON", "en")[0]["qid"] == 192   # rank title stripped
    assert link.resolve(k, "Moscow", "GPE", "en")[0]["qid"] == 649
    assert link.resolve(k, "Wickham", "PERSON", "en")[3] == "type-mismatch"      # the town isn't the officer
    assert link.resolve(k, "Elizabeth", "PERSON", "en")[3] == "type-mismatch"    # a given name isn't a person
    assert link.resolve(k, "Darcy", "PERSON", "en")[3] == "fictional"
    assert link.resolve(k, "Mr. Napoleon", "PERSON", "en")[3] == "social-title"
    assert link.resolve(k, "French", "NORP", "en")[3] == "label-NORP"
    assert link.resolve(k, "Atlantis", "LOC", "en")[3] == "no-article"
    assert link.resolve(k, "Napoleon Bonaparte", "PERSON", "en")[0]["qid"] == 517          # a longer form of his name
    assert link.resolve(k, "Maria", "PERSON", "en")[3] == "name-disagrees"                 # a redirect to a skier
    assert link.resolve(k, "moscow", "GPE", "en")[3] == "not-a-name"
    assert link.resolve(k, "Moscow", "GPE", "en", book_title="Moscow")[3] == "the-book-itself"
    assert link.resolve(k, "Moscow", "GPE", "en", book_title="Moscow Nights")[0]["qid"] == 649


def test_the_book_rules_out_common_words_late_births_its_own_people_and_wrong_accents(tmp_path):
    k = kb(tmp_path)
    spans = [{"text": "Rostóv", "label": "PERSON"}] * 3 + [{"text": "Rostóv", "label": "GPE"}]
    wp = link.BookFacts(["He heard Mass. They sang the mass at dawn."], spans, year=1869)
    assert link.resolve(k, "Wittgenstein", "PERSON", "en", facts=wp)[3] == "after-the-book"
    assert link.resolve(k, "Kutúzov", "PERSON", "en", facts=wp)[0]["qid"] == 192
    assert link.resolve(k, "Rostóv", "GPE", "en", facts=wp)[3] == "a-person-here"
    assert link.resolve(k, "Mass", "GPE", "en", facts=wp)[3] == "common-word"
    assert link.resolve(k, "Morón", "LOC", "es")[3] == "name-disagrees"


def test_light_takes_the_first_mention_per_chapter_and_caps_density():
    def r(start, qid, sl, word, alts=()):
        return dict(book="b", href="c1", start=start, word=word, qid=qid, sl=sl, sim=0.5, alts=list(alts), label_wd=str(qid))
    rows = [r(0, 1, 300, 0), r(10, 1, 300, 5), r(20, 2, 50, 10), r(30, 3, 20, 20), r(40, 4, 100, 299),
            r(50, 5, 90, 100, alts=[[9, "x", 80]])]
    light = ll_select.select(rows, "light", min_sl=40, min_sim=0.3, per_words=150)
    assert [x["qid"] for x in light] == [1, 4]          # 300 words -> 2 links: the best-known two, in order
    more = ll_select.select(rows, "more", min_sl=40)
    assert [x["qid"] for x in more] == [1, 2, 3, 4]     # 5 stays out: ambiguous


def test_titled_names_saints_and_given_names_stay_unlinked(tmp_path):
    k = kb(tmp_path)
    dq = link.BookFacts(["los amores de don Lanzarote con la reina Ginebra."], [], year=1615, lang="es")
    assert link.resolve(k, "Ginebra", "LOC", "es", facts=dq)[3] == "a-person-here"    # the queen, not Geneva
    assert link.resolve(k, "Ginebra", "LOC", "es")[0]["qid"] == 71
    wp = link.BookFacts(["They loved old Moscow. He was the master.\nMoscow burned."], [], lang="en")
    assert link.resolve(k, "Moscow", "GPE", "en", facts=wp)[0]["qid"] == 649      # adjectives and sentence breaks
    assert link.resolve(k, "Santa Lucía", "LOC", "es")[3] == "a-saint"
    assert link.resolve(k, "Teodora", "PER", "es")[3] == "a-given-name"
    assert link.resolve(k, "Napoleon", "PERSON", "en")[0]["qid"] == 517
