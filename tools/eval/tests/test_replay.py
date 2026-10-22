"""The port must score and rank as core/research does; cases mirror LexicalScoreTest and RankingTest."""
import pytest

import replay
from replay import Config, by_lexical, cap_media, lift_media, preselect, scorer

CFG = Config()


def P(text, title="Untitled", heading="", kind="BOOK", source="id", location="", score=0.0):
    return {"kind": kind, "source": source, "title": title, "location": location, "heading": heading, "text": text, "searchScore": score}


def of(p, keywords):
    return scorer(keywords)(p)


def test_fraction_of_keywords_present():
    assert of(P("Steam drives the piston in a boiler."), ["steam", "piston", "gauge", "valve"]) == pytest.approx(0.5)


def test_title_is_only_a_bonus():
    assert of(P("nothing here", title="Steam engine", kind="ARTICLE"), ["steam"]) == pytest.approx(0.1)
    assert of(P("steam power", title="Steam engine"), ["steam"]) == pytest.approx(1.1)
    assert of(P("She walked to the station.", title="Sister Carrie"), ["sister", "phone number"]) == pytest.approx(0.1)


def test_whole_words_only():
    assert of(P("Please concatenate the strings."), ["cat"]) == 0.0


def test_heading_counts_and_phrases_match_word_by_word():
    assert of(P("text", heading="Boiler Pressure"), ["boiler pressure"]) == pytest.approx(1.0)
    portia = P("The quality of mercy is not strained, said Portia.")
    assert of(portia, ["portia mercy", "quality mercy"]) == pytest.approx(1.0)
    assert of(portia, ["portia mercy", "portia quote", "literature analysis"]) == pytest.approx(0.5)


def test_stopwords_in_a_keyword_are_ignored():
    assert of(P("Open the doors."), ["the library", "doors"]) == pytest.approx(0.5)


def test_lead_gets_the_bonus_only_when_it_matches():
    def a(heading, text, kind="ARTICLE"):
        return P(text, title="Overview", heading=heading, kind=kind)
    kw = ["steam", "valve"]
    assert of(a(" overview ", "steam"), kw) == pytest.approx(0.8)
    assert of(a("", "steam"), kw) == pytest.approx(0.8)
    assert of(a("Overview", "river"), kw) == 0.0
    assert of(a("History", "steam"), kw) == pytest.approx(0.5)
    assert of(a("", "steam", kind="BOOK"), kw) == pytest.approx(0.5)


def test_article_title_lead_and_alias():
    bible = P("For the love of money is the root of all evil.", title="Bible", kind="ARTICLE")
    assert of(bible, ["bible", "money", "evil"]) == pytest.approx(2 / 3 + 0.1 + 0.3)
    assert of(P("Charity is a duty.", title="The Koran"), ["quran", "charity"]) == pytest.approx(0.6)


def test_case_folds_ascii_only_as_kotlin_does():
    assert of(P("ÉCOLE de Paris"), ["école"]) == 0.0
    assert of(P("STEAM"), ["steam"]) == pytest.approx(1.0)


def test_no_keywords_scores_zero():
    assert of(P("text"), []) == 0.0


def book(source, text, n=0, score=0.0):
    return P(text, title="Title " + source, source=source, location="%s-%d" % (source, n), score=score)


def test_preselect_caps_per_document_and_keeps_twelve():
    a = [dict(book("A", "steam engine", i), location="A-1") for i in range(6)]
    b = [book("B", "steam", i) for i in range(2)]
    out = preselect(a + b, ["steam", "engine"], CFG)
    assert sum(p["source"] == "A" for p in out) == 3 and sum(p["source"] == "B" for p in out) == 2
    assert len(preselect([book("S%d" % i, "steam", i) for i in range(30)], ["steam"], CFG)) == 12


def test_preselect_breaks_ties_by_search_score():
    low, high = book("A", "steam", 1, score=1.0), book("B", "steam", 1, score=5.0)
    assert preselect([low, high], ["steam"], CFG)[0] is high


def test_media_never_takes_preselect_slots():
    strong = [book("S%d" % i, "steam engine", i) for i in range(12)]
    weaker = [book("S%d" % i, "steam", i) for i in range(12, 15)]
    media = [P("Album: Steam\nTracks: steam engine", title="Steam", kind="MEDIA", source="album:a1"),
             P("Album: Engines\nTracks: steam engine", title="Engines", kind="MEDIA", source="album:a2")]
    with_media = preselect(media + strong + weaker, ["steam", "engine"], CFG)
    assert [p for p in with_media if p["kind"] != "MEDIA"] == strong
    assert [p for p in with_media if p["kind"] == "MEDIA"] == media


def test_lexical_rank_drops_below_threshold():
    full, two, one = book("A", "steam engine boiler", 1), book("B", "steam engine", 2), book("C", "steam", 3)
    assert by_lexical([one, two, full], ["steam", "engine", "boiler"], CFG) == [full, two]


def test_media_cap_lift_and_cue():
    m1, m2 = (P("Album %d" % n, kind="MEDIA", source="album:a%d" % n) for n in (1, 2))
    b1, b2 = book("B1", "steam", 1), book("B2", "steam", 2)
    assert cap_media([m1, b1, m2, b2], False, CFG) == [m1, b1, b2]
    assert cap_media([m1, b1, m2, b2], True, CFG) == [m1, b1, m2, b2]
    assert lift_media([b1, b2], [b1, m2, b2, m1], True) == [m2, b1, b2]
    assert lift_media([b1, m2], [b1, m2], True) == [m2, b1]
    assert lift_media([b1, b2], [m1, b1, b2], False) == [b1, b2]
    assert replay.media_asked("Do I have any Holst?") and replay.media_asked("Which ALBUM?")
    assert not replay.media_asked("What was the final score last night?")


def test_agrees_allows_the_prompt_to_trim_from_the_end():
    a, b, c = book("A", "x"), book("B", "x"), book("C", "x")
    assert replay.agrees([a, b], [a, b, c])
    assert not replay.agrees([b], [a, b])
    assert replay.agrees([], []) and not replay.agrees([], [a])


def test_evaluate_counts_recall_ceiling_and_empty():
    items = {
        "a": {"id": "a", "question": "q", "answerable": True, "answers": [{"source": "wiki", "location": "Paris", "quote": "capital of France"}]},
        "u": {"id": "u", "question": "q", "answerable": False, "answers": []},
    }
    hit = P("Paris is the capital of France.", title="Paris", kind="ARTICLE", source="wiki", location="Paris")
    corpus = {"a": {"id": "a", "keywords": ["paris"], "passages": [hit]}, "u": {"id": "u", "keywords": ["zzz"], "passages": [book("B", "river")]}}
    r = replay.evaluate(items, corpus, CFG)
    assert (r["location"], r["quote"], r["inCorpus"], r["empty"]) == (1, 1, 1, 1)


def test_a_keyword_counts_for_its_best_alternative():
    kjv = P("Wine is a mocker, strong drink is raging.", title="The King James Bible")
    assert of(kjv, ["alcohol|strong drink|wine", "tomorrow"]) == pytest.approx(0.5)
    assert of(P("strong winds"), ["alcohol|strong spirits"]) == pytest.approx(0.5)
    assert of(P("nothing here", title="Wine"), ["alcohol|wine"]) == pytest.approx(0.1)
