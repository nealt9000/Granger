"""Multi-step questions answered as simple ones: the split, #refs, and the prompts."""
import multistep as ms


def test_a_numbered_split_is_read_in_order_and_capped():
    assert ms.parse_split("1. Who taught Alexander?\n2. Who taught #1?\n") == ["Who taught Alexander?", "Who taught #1?"]
    assert ms.parse_split("Sure:\n1) A?\n2) B?\n3) C?\n4) D?") == ["A?", "B?", "C?"]
    assert ms.parse_split("1. A?\n3. C?") == ["A?"]  # numbering must run on
    assert ms.parse_split("No list here.") == []


def test_a_ref_is_filled_with_the_first_sentence_of_its_answer_without_citations():
    answers = {1: "Alexander was tutored by Aristotle [1]. He was 13 [2]."}
    assert ms.fill("Who taught #1?", answers) == "Who taught (Alexander was tutored by Aristotle)?"
    assert ms.fill("Who taught #1?", {}) is None
    assert ms.fill("Who taught #1?", {1: None}) is None
    assert ms.fill("When was Darwin born?", {}) == "When was Darwin born?"


def test_citations_move_with_their_passages_into_the_combined_prompt():
    assert ms.renumber("Born 1809 [1], died 1882 [2, 3].", {1: 4, 2: 5}) == "Born 1809 [4], died 1882 [5]."
    assert ms.renumber("Only [3].", {1: 1}) == "Only."


def test_the_answer_prompt_is_the_apps_with_numbered_passages_and_notes():
    p = ms.answer_prompt("Who was older?", [{"title": "Charles Darwin", "heading": "Early life", "text": "Born 1809."}],
                         notes=[("When was Darwin born?", "1809 [1].")])
    assert p.startswith("You are Granger's research librarian.")
    assert "[1] Charles Darwin · Early life\nBorn 1809." in p
    assert "- When was Darwin born? 1809 [1]." in p
    assert p.endswith("Question: Who was older?\nAnswer:")


def test_a_part_under_the_gate_gets_no_passages():
    assert ms.pick([{"cosine": 0.77, "source": "s", "location": "l", "text": "x"}]) == []
    hits = [{"cosine": 0.80 - i / 100, "source": "s", "location": "l%d" % (i // 3), "text": "t%d" % i} for i in range(9)]
    assert [p["text"] for p in ms.pick(hits)] == ["t0", "t1", "t3", "t4"]  # two per document, four in all


def test_a_simple_reply_is_no_split():
    assert ms.parse_split("SIMPLE") == []
    assert ms.split_prompt("How did he die?", "Who was Caesar?", "v2").endswith("Previous question: Who was Caesar?\nQuestion: How did he die?")


def test_dates_are_read_in_either_order_and_bc_years_are_negative():
    assert ms.when("Darwin was born on February 12, 1809 [3].") == (1809, 2, 12)
    assert ms.when("Cervantes died on 22 April 1616.") == (1616, 4, 22)
    assert ms.when("Columbus reached the Americas in 1492 [3, 4].") == (1492, 0, 0)
    assert ms.when("Caesar died in 44 BC.") == (-44, 0, 0)
    assert ms.when("He lived to 969 years of age.") is None


def test_the_time_between_two_years_and_which_came_first_are_worked_out():
    facts = ms.worked([("When did Columbus reach the Americas?", "Columbus reached the Americas in 1492 [3]."),
                       ("When did the Mayflower arrive?", "The Mayflower arrived on November 21, 1620 [1].")])
    assert facts == ['1492 ("When did Columbus reach the Americas?") is earlier than November 21, 1620 '
                     '("When did the Mayflower arrive?"), by 128 years.']


def test_two_full_dates_give_years_and_months_or_the_same_day():
    span = ms.worked([("When was the Origin published?", "On November 24, 1859 [1]."),
                      ("When did Darwin die?", "Darwin died on April 19, 1882 [2].")])
    assert span[0].endswith("by 22 years and 4 months.")
    same = ms.worked([("When was Darwin born?", "Darwin was born on February 12, 1809 [3]."),
                      ("When was Lincoln born?", "Lincoln was born on February 12, 1809 [1].")])
    assert same == ['"When was Darwin born?" and "When was Lincoln born?" give the same date, February 12, 1809.']


def test_amounts_with_the_same_unit_are_compared_and_others_are_left_alone():
    facts = ms.worked([("How old was Methuselah?", "Methuselah lived to 969 years of age [2]."),
                       ("How old was Noah?", "Noah died at 950 years old [4].")])
    assert facts == ['969 years ("How old was Methuselah?") is more than 950 years ("How old was Noah?"), by 19 years.']
    assert ms.worked([("How tall?", "It is 300 metres tall."), ("How old?", "It is 130 years old.")]) == []


def test_the_strict_gate_wants_answered_parts_from_two_articles_the_question_had_not_reached():
    def part(title, answer="Yes [1]."):
        return {"passages": [{"title": title}], "answer": answer}
    state = {"q#1": part("Charles Darwin"), "q#2": part("Abraham Lincoln")}
    rec = {"id": "q", "chosen": [{"title": "Charles Darwin"}]}
    assert ms.strict_ok(rec, ["a?", "b?"], state)
    assert not ms.strict_ok({"id": "q", "chosen": [{"title": "Charles Darwin"}, {"title": "Abraham Lincoln"}]},
                            ["a?", "b?"], state)
    assert not ms.strict_ok(rec, ["a?", "b?"], {"q#1": part("Methuselah"), "q#2": part("Methuselah")})
    assert not ms.strict_ok(rec, ["a?", "b?"], {"q#1": part("Charles Darwin"), "q#2": part("Abraham Lincoln", None)})
