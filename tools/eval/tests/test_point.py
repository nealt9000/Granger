import point


def test_split_keeps_lines_and_sentences():
    text = "Born: 1797\nShe wrote Frankenstein. It was published in 1818.\n\n—"
    assert point.split_sentences(text) == ["Born: 1797", "She wrote Frankenstein.", "It was published in 1818."]


def test_parse_accepts_one_or_two_numbers_and_zero():
    assert point.parse("3", 5) == ([3], True)
    assert point.parse(" 4, 2\n", 5) == ([2, 4], True)
    assert point.parse("2.", 5) == ([2], True)
    assert point.parse("0", 5) == ([], True)


def test_parse_rejects_prose_and_out_of_range():
    assert point.parse("Sentence 3", 5) == ([], False)
    assert point.parse("6", 5) == ([], False)
    assert point.parse("", 5) == ([], False)
    assert point.parse("1,2,3", 5) == ([], False)


def test_gold_marks_overlapping_sentences_across_a_break():
    sents = ["Jonah was a prophet.", "The Lord prepared a great fish.", "It swallowed Jonah whole."]
    assert point.gold(sents, [{"quote": "a great fish. It swallowed"}]) == {2, 3}
    assert point.gold(sents, [{"quote": "Prophet"}]) == {1}
    assert point.gold(sents, [{"quote": "a whale"}]) == set()


def test_judge_buckets():
    meta = {"passages": [{"rank": 1, "gold": [2]}, {"rank": 2, "gold": []}]}
    yes, no = {"answerable": True}, {"answerable": False}
    assert point.judge(yes, meta, [(1, [2])]) == "hit"
    assert point.judge(yes, meta, [(2, [1])]) == "wrong"
    assert point.judge(yes, meta, [(2, [1]), (1, [2, 3])]) == "hit"
    assert point.judge(yes, meta, []) == "none"
    assert point.judge(no, meta, []) == "declined"
    assert point.judge(no, meta, [(1, [1])]) == "pointed"


def test_prompt_names_one_passage_and_asks_for_a_number():
    p = point.point_prompt("Who?", "Jonah · Wikipedia", ["A.", "B."], prior_question="What fish?")
    assert "Passage: Jonah · Wikipedia\n1. A.\n2. B." in p
    assert "Earlier question: What fish?\nQuestion: Who?" in p
    assert p.endswith("Sentence number:")
    assert "Passage:\n1. A." in point.point_prompt("Who?", "Jonah", ["A."], shape="bare")


def test_quote_reply_names_the_title():
    p = {"passage": {"title": "Jonah"}, "header": "Jonah · Wikipedia", "sentences": ["A.", "He was swallowed.", "C."]}
    assert point.quote_reply(p, [2]) == 'Jonah: "He was swallowed." [1]'


def test_say_check_allows_only_words_from_the_sentences():
    s = ["After he is swallowed by a large sea creature, he returns."]
    assert point.say_check("Jonah was swallowed by a large sea creature.", "who got swallowed by a fish", "Jonah", s) == (True, [])
    assert point.say_check("Jonah was swallowed by a whale.", "who got swallowed", "Jonah", s) == (False, ["whale"])
    assert point.say_check("NOT IN LIBRARY", "who", "Jonah", s)[0] is False


def test_choose_prompt_lists_candidates_with_titles():
    p = point.choose_prompt("Who?", [("Jonah", "He was swallowed."), ("Leviathan", "It eats whales.")])
    assert "1. (Jonah) He was swallowed.\n2. (Leviathan) It eats whales." in p
    assert p.endswith("Sentence number:")
