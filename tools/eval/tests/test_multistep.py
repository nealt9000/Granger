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
