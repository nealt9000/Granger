import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import count  # noqa: E402
import recall  # noqa: E402

HERE = Path(__file__).resolve().parents[1]


def test_default_set_is_english_and_unchanged():
    assert count.load_set() == json.loads((HERE / "set.json").read_text())
    assert count.books_label(count.load_set()) == "granger-classics"


def test_spanish_set_loads_by_path():
    es = count.load_set(HERE / "set-es.json")
    assert count.books_label(es) == "clasicos-granger"
    assert all("_es_" in p["id"] for p in es["packs"])
    english = {p["id"] for p in count.load_set()["packs"]}
    assert not english & {p["id"] for p in es["packs"]}


def item(id, group, answerable, source="wikipedia_es_top"):
    return {"id": id, "group": group, "answerable": answerable,
            "answers": [{"source": source, "location": "X", "quote": "q"}] if answerable else []}


def test_recall_table_counts_only_questions_in_the_set():
    items = [item("a", "g", True), item("b", "g", True), item("c", "g", True, "wiktionary_es_all"), item("d", "g", False)]
    firsts = {"a": 0, "b": 7, "c": None, "d": None}
    top = {"a": 0.8, "b": 0.7, "c": 0.9, "d": 0.79}
    lines = recall.recall_table(items, firsts, top, {"wikipedia_es_top"}, (4, 12))
    assert lines[-1].split()[:4] == ["all", "2/3", "1/2", "2/2"]
    assert lines[-1].endswith("1/2 ans, 1/1 unans")


def test_first_dense_hit_by_location_or_quote():
    answers = [{"source": "wikipedia_es_top", "location": "Madrid", "quote": "capital de españa"}]
    by_quote = {"source": "wikipedia_es_top", "location": "Otra", "heading": "", "text": "Es la Capital de  España."}
    by_loc = {"source": "wikipedia_es_top", "location": "Madrid#Historia", "heading": "", "text": "nada"}
    miss = {"source": "wikivoyage_es_all", "location": "Madrid", "heading": "", "text": "nada"}
    assert recall.first_dense_hit([miss, by_quote], answers) == 1
    assert recall.first_dense_hit([miss, by_loc], answers) == 1
    assert recall.first_dense_hit([miss], answers) is None
