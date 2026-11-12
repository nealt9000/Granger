import known_items
import search_score as ss


def test_known_rank_matches_file_and_path_or_the_title_in_the_same_set():
    item = {"pack": "wikipedia_en_top_maxi_2026-09-14.zim", "path": "Led_Zeppelin", "title": "Led Zeppelin"}
    hits = [
        {"file": "wikivoyage_en_all_maxi.zim", "path": "Led_Zeppelin", "title": "Led Zeppelin"},  # another set
        {"file": "wikipedia_en_history_maxi.zim", "path": "Led_Zeppelin", "title": "Led Zeppelin"},  # same set, other slice
    ]
    assert ss.known_rank(item, hits) == 2
    assert ss.known_rank(item, hits[:1]) is None


def test_answer_rank_by_article_or_book():
    item = {"answers": [{"source": "wikipedia_en_medicine", "location": "Burn"}, {"source": "book:granger:gc-emma", "location": "c1.xhtml"}]}
    assert ss.answer_rank(item, [{"source": "wikipedia_en_medicine", "path": "Scald"}, {"source": "wikipedia_en_medicine", "path": "Burn"}]) == 2
    assert ss.answer_rank(item, [{"source": "granger:gc-emma"}], books=True) == 1


def test_score_known_counts_by_language_and_kind():
    items = [
        {"id": "a", "language": "en", "kind": "exact", "query": "x", "pack": "p_en_x.zim", "path": "X", "title": "X"},
        {"id": "b", "language": "en", "kind": "typo", "query": "y", "pack": "p_en_x.zim", "path": "Y", "title": "Y"},
    ]
    run = {
        "a": {"titles": [{"file": "p_en_x.zim", "path": "X", "title": "X"}], "fullText": []},
        "b": {"titles": [], "fullText": [{"file": "p_en_x.zim", "path": "Y", "title": "Y"}]},
    }
    lines, misses = ss.score_known(items, run)
    assert "| all | 2 | 1/2 (50%) | 1/2 (50%) | 0.50 | 1/2 (50%) | 1/2 (50%) |" in lines
    assert [m[0]["id"] for m in misses] == ["b"]


def test_known_items_queries():
    import random
    assert known_items.fold("René Descartes") == "rene descartes"
    assert known_items.prefix("Photosynthesis") == "photosyn"
    assert known_items.prefix("Ozone") is None
    assert not known_items.usable("List of rivers")
    assert not known_items.usable("Emma — CHAPTER IV", "granger_classics_en.zim", "emma/005")
    assert not known_items.usable("Robert Louth", "ifixit_en_all.zim", "User/3051326/Robert Louth")
    slip = known_items.typo("Photosynthesis", random.Random(1))
    assert slip != "Photosynthesis" and slip[0] == "P" and abs(len(slip) - 14) <= 1
