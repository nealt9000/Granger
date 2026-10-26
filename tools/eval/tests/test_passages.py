import passages

PROMPT = """Rules.

Passages:
[1] Mary Shelley (2026) · Wikipedia · Mary Shelley
She was born in 1797. She wrote Frankenstein.

[2] Frankenstein (2026) · Wikipedia · Frankenstein
Published in 1818.

Question: How old was she?
Answer: """


def test_a_prompt_splits_into_its_passages_and_joins_back_unchanged():
    head, ps, tail = passages.split_prompt(PROMPT)
    assert [h for h, _ in ps] == ["Mary Shelley (2026) · Wikipedia · Mary Shelley", "Frankenstein (2026) · Wikipedia · Frankenstein"]
    assert ps[1][1] == "Published in 1818."
    assert passages.join_prompt(head, ps, tail) == PROMPT


def test_trim_keeps_the_best_sentence_with_its_neighbours_in_order():
    text = "One is here. Two is here. Three is best. Four is here. Five is here. Six is far."
    assert passages.trim(text, [0.1, 0.2, 0.9, 0.3, 0.1, 0.1], 40) == "Two is here. Three is best. Four is here."
    assert passages.trim(text, [0.1, 0.2, 0.9, 0.3, 0.1, 0.1], 1000) == text


def test_the_gate_drops_low_passages_renumbers_and_empties_to_a_decline():
    rec = {"id": "x", "prompts": [{"stage": "answer", "prompt": PROMPT}],
           "chosen": [{"text": "She was born in 1797. She wrote Frankenstein."}, {"text": "Published in 1818."}]}
    score = {"passages": [0.5, 0.9], "sentences": [[0.5, 0.5], [0.9]]}
    prompt, chosen = passages.rebuild(rec, {}, score, "gate", 0.7, 500)
    assert "[1] Frankenstein" in prompt and "[2]" not in prompt and chosen == [rec["chosen"][1]]
    assert passages.rebuild(rec, {}, score, "gate", 0.95, 500) == (None, [])
