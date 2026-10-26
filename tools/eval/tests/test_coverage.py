import coverage


def test_a_quote_is_found_with_case_dashes_and_spacing_folded():
    chosen = [{"source": "wiki", "location": "Mary_Shelley", "text": "Mary Shelley (30 August 1797 – 1 February\n1851)"}]
    assert coverage.mark({"source": "wiki", "location": "Mary_Shelley", "quote": "30 AUGUST 1797 - 1 february 1851"}, chosen) == "passage"


def test_the_right_article_without_the_quote_is_article_and_another_is_no():
    chosen = [{"source": "wiki", "location": "Frankenstein#Publication", "text": "The novel was well received."}]
    assert coverage.mark({"source": "wiki", "location": "Frankenstein", "quote": "published 1 january 1818"}, chosen) == "article"
    assert coverage.mark({"source": "wiki", "location": "Mary_Shelley", "quote": "30 august 1797"}, chosen) == "no"


def test_a_grounded_single_fact_reply_holds_the_answer_and_multi_step_counts_facts():
    assert coverage.holds({"multi": False, "facts": ["no"], "outcome": "grounded"}) == "in passage"
    assert coverage.holds({"multi": False, "facts": ["article", "no"], "outcome": "declined"}) == "article only"
    assert coverage.holds({"multi": True, "facts": ["passage", "article"], "outcome": "grounded"}) == "one"
    assert coverage.holds({"multi": True, "facts": ["passage", "passage"], "outcome": "declined"}) == "both"
