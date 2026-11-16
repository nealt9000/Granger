"""Tests for the book manifest builder and check (Plan KB2). Fixtures: tools/test-zim/make_bookpacks_zim.py."""
import copy
import json
import pathlib
import sqlite3
import sys

import pytest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "sidecar"))
import build  # noqa: E402
import check  # noqa: E402
import meta  # noqa: E402

ZIMIT = str(HERE / "testdata" / "zimit-books.zim")
KOLIBRI = str(HERE / "testdata" / "kolibri-books.zim")
ZIMIT_CFG = {"extractor": "zimit-index", "title": "Example Library", "noun": "books", "shelf": "manuals",
             "groupBy": "page", "groupPattern": "^Library-(?P<g>.+)$", "yearSuffix": True, "minBytes": 1500,
             "site": "Example Press"}
KOLIBRI_CFG = {"extractor": "kolibri", "title": "Example Storybooks", "noun": "books", "shelf": "children"}


@pytest.fixture(scope="module")
def zimit():
    return build.build("example.org_en_all", ZIMIT, ZIMIT_CFG)


@pytest.fixture(scope="module")
def kolibri():
    return build.build("storybooks_mul_all", KOLIBRI, KOLIBRI_CFG)


def by_key(m):
    return {i["key"]: i for i in m["items"]}


# ---------- zimit-index ----------

def test_titles_come_from_the_best_source_the_pages_give(zimit):
    titles = {k.removeprefix("www.example.org/"): (i["title"], i["titleSource"]) for k, i in by_key(zimit).items()}
    assert titles == {
        "library/a_treatise_on_hedges_1856.pdf": ("A Treatise On Hedges (1856)", "block"),   # the table row, tidied
        "library/bee_keeping_for_all_1902.pdf": ("Bee Keeping For All (1902)", "block"),
        "files/field-radio.pdf": ("Field Radio Basics", "page"),        # one hop back from its download page
        "manuals/files/water-pt1.pdf": ("Water Purification (Part 1 of 2)", "pdf"),  # brackets inside a PDF string
        "manuals/files/water-pt2.pdf": ("Water Purification (Part 2 of 2)", "pdf"),
        "manuals/files/knots.pdf": ("Knots: A Visual Guide", "heading"),  # a generic "Download PDF" under a heading
        "unlinked/sea-survival.pdf": ("Sea Survival Handbook", "pdf"),   # linked from nowhere: its own Info title
    }


def test_a_small_file_is_left_out_by_its_exact_size(zimit):
    assert "www.example.org/manuals/files/leaflet.pdf" not in by_key(zimit)
    assert zimit["stats"]["docsInPack"] == 8


def test_volatile_query_parameters_leave_the_key_but_not_the_path(zimit):
    item = by_key(zimit)["www.example.org/files/field-radio.pdf"]
    assert item["path"] == "www.example.org/files/field-radio.pdf?tmstv=1786&nonce=ab12"


def test_parts_with_no_whole_file_share_a_work(zimit):
    items = by_key(zimit)
    one, two = items["www.example.org/manuals/files/water-pt1.pdf"], items["www.example.org/manuals/files/water-pt2.pdf"]
    assert one["work"] == two["work"] == {"key": "www.example.org/manuals/files/water", "title": "Water Purification"}
    assert (one["order"], two["order"]) == (1, 2)


def test_groups_come_from_the_page_title_only_where_the_pattern_matches(zimit):
    groups = {k.rsplit("/", 1)[-1]: i["group"] for k, i in by_key(zimit).items()}
    assert groups["a_treatise_on_hedges_1856.pdf"] == "Farming and Gardens"
    assert groups["field-radio.pdf"] == "" and groups["knots.pdf"] == ""


def test_a_moved_file_keeps_its_key(zimit):
    previous = copy.deepcopy(zimit)
    moved = by_key(previous)["www.example.org/manuals/files/knots.pdf"]
    moved["key"] = "www.example.org/old/knots.pdf"
    again = build.build("example.org_en_all", ZIMIT, ZIMIT_CFG, previous)
    assert "www.example.org/old/knots.pdf" in by_key(again)
    assert again["stats"]["keysKept"] == 1


# ---------- kolibri ----------

def test_kolibri_items_carry_language_level_author_and_licence(kolibri):
    items = by_key(kolibri)
    goat = items["a1111111111111111111111111111111"]
    assert goat | {} == goat and goat["path"] == "e1.epub" and goat["format"] == "epub"
    assert (goat["title"], goat["language"], goat["group"], goat["author"]) == \
        ("The Hungry Goat", "en", "Level 1", "Ann Writer")
    assert goat["licence"] == "CC BY by Example Storybooks"
    assert goat["description"] == "A goat eats everything."
    assert items["b2222222222222222222222222222222"]["language"] == "sw"
    assert len(items) == 2


# ---------- the sidecar file ----------

def test_the_sidecar_keeps_the_meta_contract_and_reads_back(zimit, tmp_path):
    out = tmp_path / "example.org_en_all_2026-10.gbooks"
    build.write_gbooks(zimit, out, "example.org_en_all_2026-10.zim", meta.sha256(ZIMIT))
    assert meta.check(out, ZIMIT) == []
    back = build.read_gbooks(out)
    assert back["meta"]["kind"] == "gbooks" and back["meta"]["schemaVersion"] == "1"
    assert back["collection"]["title"] == "Example Library" and back["collection"]["shelf"] == "manuals"
    strip = lambda i: {k: i[k] for k in ("key", "path", "title", "group", "bytes", "format")}  # noqa: E731
    assert [strip(i) for i in back["items"]] == [strip(i) for i in zimit["items"]]
    water = by_key(back)["www.example.org/manuals/files/water-pt2.pdf"]
    assert water["work"]["title"] == "Water Purification" and water["order"] == 2
    db = sqlite3.connect(out)
    assert [r[0] for r in db.execute("SELECT name FROM groups ORDER BY ord")] == ["Farming and Gardens"]


# ---------- check ----------

def write(tmp_path, manifest, name="x.gbooks"):
    out = tmp_path / name
    build.write_gbooks(manifest, out, "example.org_en_all_2026-10.zim", meta.sha256(ZIMIT))
    return str(out)


def test_check_passes_a_good_manifest_against_its_source(zimit, tmp_path):
    assert check.main([write(tmp_path, zimit), "--source", ZIMIT]) == 0


def test_check_fails_an_empty_title_and_a_path_the_source_lacks(zimit, tmp_path):
    bad = copy.deepcopy(zimit)
    bad["items"][0]["title"] = " "
    bad["items"][1]["path"] = "www.example.org/nowhere.pdf"
    probs = check.problems_of(bad, ZIMIT)
    assert any("empty title" in p for p in probs) and any("nowhere.pdf" in p for p in probs)
    assert check.main([write(tmp_path, bad), "--source", ZIMIT]) == 1


def test_check_reports_drift_when_books_are_lost(zimit, tmp_path):
    previous = copy.deepcopy(zimit)
    previous["items"].append(dict(previous["items"][0], key="www.example.org/gone.pdf", title="Gone Book"))
    assert "1 books lost (keys gone): 'Gone Book'" in check.drift_of(zimit, previous)
    assert check.main([write(tmp_path, zimit), "--previous", write(tmp_path, previous, "p.gbooks")]) == 2


def test_check_reports_drift_when_titles_fall_back_to_file_names(zimit):
    worse = copy.deepcopy(zimit)
    for i in worse["items"]:
        i["titleSource"] = "filename"
    assert any("file names" in d for d in check.drift_of(worse, zimit))
    assert check.drift_of(zimit, zimit) == []


# ---------- helpers ----------

@pytest.mark.parametrize("text, expected", [
    ("Download PDF (13MB)", False), ("PDF 42 mb", False), ("please click here to start it manually", False),
    ("Download Page", False), ("Part 2", False), ("Download PDF (English)", False), ("Lab Manual PDF", False),
    ("German", False), ("Everything", False),
    ("First Aid for Soldiers", True), ("Recursion", True), ("Field Radio Basics", True),
])
def test_specific_link_texts(text, expected):
    assert build.specific(text) is expected


def test_qualifiers_and_tidying():
    assert build.qualifier("Download Lab Manual PDF") == "Lab manual"
    assert build.qualifier("Download PDF (2MB)") is None
    assert build.tidy("2026-07-01 A_Biographical_Dictionary-Containing_All_The_Engravers_1785 42 mb",
                      {"yearSuffix": True}) == "A Biographical Dictionary – Containing All The Engravers (1785)"
    assert build.tidy("Scientific_American_1906-01-20 3 mb", {}) == "Scientific American 1906-01-20"
    assert build.tidy("Effects_Of_Nuclear_Weapons_3Rd_Ed 40Mm", {}) == "Effects Of Nuclear Weapons 3rd Ed 40mm"
    assert build.tidy("Aerial_Flight_Vol_1-Aerodynamics_1907", {"yearSuffix": True}) == "Aerial Flight Vol 1 – Aerodynamics (1907)"
    assert build.tidy("Review_Vol_11-14_1897", {"yearSuffix": True}) == "Review Vol 11-14 (1897)"


def test_pdf_titles_come_from_the_info_dictionary_only():
    info = b"1 0 obj << /Title (Table 1) >> endobj 9 0 obj << /Title (Real \\(Title\\) (2nd)) /Producer (X) >> endobj"
    assert build.pdf_title(info, b"") == "Real (Title) (2nd)"
    assert build.pdf_title(b"<< /Title (Total) >>", b"") is None
    xmp = b"<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Sea &amp; Shore</rdf:li></rdf:Alt></dc:title>"
    assert build.pdf_title(b"", xmp) == "Sea & Shore"
    assert build.plausible_pdf_title("Microsoft Word - Hospital Corpsman.doc") == "Hospital Corpsman"
    assert build.plausible_pdf_title("staircase.graffle") is None


def test_keys_and_path_titles():
    assert build.key_of("https://x.org/download/a/?tmstv=1&dlm-dp-dl-nonce=z") == "x.org/download/a/"
    assert build.key_of("x.org/cgi/viewcontent.cgi?article=1028&context=oer") == \
        "x.org/cgi/viewcontent.cgi?article=1028&context=oer"
    rule = {"match": r"hwex/(?P<t>[fs])(?P<yy>\d\d)/(?P<kind>hw|lab)(?P<n>\w*)\.pdf$", "title": "{t} {yy} · {kind} {n}",
            "maps": {"t": {"f": "Fall", "s": "Spring"}, "kind": {"hw": "Homework", "lab": "Lab"}}}
    assert build.path_title("a/hwex/f24/hw2.pdf", [rule]) == "Fall 2024 · Homework 2"
    assert build.path_title("a/hwex/s98/lab1.pdf", [rule]) == "Spring 1998 · Lab 1"
    assert build.path_title("a/hwex/s98/lab1.pdf", [rule], fallback=True) is None


def test_every_configured_pack_names_a_known_extractor_and_shelf():
    cfg = json.loads((HERE / "book_packs.json").read_text(encoding="utf-8"))["packs"]
    assert cfg
    for name, pack in cfg.items():
        assert pack["extractor"] in build.EXTRACTORS, name
        assert pack["shelf"] in {"literature", "children", "manuals", "learning"}, name
        assert pack.get("noun") in {"books", "documents"}, name


def test_the_pack_name_and_catalog_lookups(tmp_path):
    assert build.zim_name_of("https://x/zim/zimit/irp.fas.org_en_military-medicine_2026-10.zim") == \
        "irp.fas.org_en_military-medicine"
    assert build.zim_name_of("/a/storybooks_mul_all_2025-01.zim") == "storybooks_mul_all"
    catalog = tmp_path / "catalog.json"
    catalog.write_text(json.dumps({"packs": [{"zimName": "storybooks_mul_all", "sha256": "abc",
        "urls": ["https://d/zim/other/storybooks_mul_all_2025-01.zim"],
        "sidecars": [{"kind": "gqid", "url": "https://r/q.gqid"}, {"kind": "gbooks", "url": "https://r/s.gbooks"}]}]}))
    assert build.from_catalog(catalog, "storybooks_mul_all", "https://m/storybooks_mul_all_2025-01.zim") == \
        ("abc", "https://r/s.gbooks")
    assert build.from_catalog(catalog, "storybooks_mul_all", "https://m/storybooks_mul_all_2026-05.zim") == \
        (None, "https://r/s.gbooks")
