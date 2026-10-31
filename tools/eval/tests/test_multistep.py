"""Multi-step questions answered as simple ones: the split, #refs, and the prompts."""
import re

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


# ---- Spanish dates (Plan L4): YearFacts' forms, keyed on the item question's languages ----

ES, BOTH = ("es",), ("en", "es")


def test_spanish_dates_months_and_de():
    assert ms.when("Napoleón murió el 5 de mayo de 1821 [1].", ES) == (1821, 5, 5)
    assert ms.when("Nació el 3 de setiembre del 1950.", ES) == (1950, 9, 3)
    assert ms.when("Cervantes murió el 22 Abril 1616.", ES) == (1616, 4, 22)
    # A month and year alone is the year, as an English "June 1815" is.
    assert ms.when("En junio de 1815 cayó Napoleón.", ES) == (1815, 0, 0)
    assert ms.when("Vivió 969 años.", ES) is None


def test_spanish_eras():
    assert ms.when("Julio César murió en el 44 a. C. [2]", ES) == (-44, 0, 0)
    assert ms.when("Fundada hacia el 1000 a.C.", ES) == (-1000, 0, 0)
    assert ms.when("Murió en el 14 d. C.", ES) == (14, 0, 0)
    assert ms.when("Nació en el año 63 antes de Cristo.", ES) == (-63, 0, 0)


def test_spanish_first_sentence_keeps_its_era():
    assert ms.first_sentence("Augusto murió en el 14 d. C. Su sucesor fue Tiberio [1].", ES) == "Augusto murió en el 14 d. C."
    assert ms.first_sentence("¿Cuándo? En 1492. ¡Sí!", ES) == "¿Cuándo?"
    assert ms.first_sentence("El Sr. Pérez llegó en 1900. Luego se fue.", ES) == "El Sr. Pérez llegó en 1900."
    # English alone breaks as before, at the first stop.
    assert ms.first_sentence("Augusto murió en el 14 d. C. Su sucesor fue Tiberio.") == "Augusto murió en el 14 d."


def test_spanish_event_words_pick_the_year_after_them():
    a = "Cervantes nació en 1547 y publicó el Quijote en 1605 [1]."
    assert ms.when(a, ES, "¿Cuándo se publicó el Quijote?") == (1605, 0, 0)
    assert ms.when(a, ES) == (1547, 0, 0)
    assert ms.when("Bolívar nació en 1783 y ganó en Boyacá el 7 de agosto de 1819.", ES, "¿Cuándo ganó Bolívar en Boyacá?") == (1819, 8, 7)


def test_spanish_ranges_give_their_end_for_an_end_word():
    q = "¿Cuándo cayó el Muro de Berlín?"
    assert ms.when("El Muro de Berlín existió de 1961 a 1989 y cayó en noviembre.", ES, q) == (1989, 0, 0)
    assert ms.when("El muro cayó; se mantuvo entre 1961 y 1989.", ES, q) == (1989, 0, 0)
    assert ms.when("La guerra terminó tras durar desde 1936 hasta 1939.", ES, "¿Cuándo terminó la guerra?") == (1939, 0, 0)
    # Without an end word a range is its start, the first date.
    assert ms.when("El Muro de Berlín existió de 1961 a 1989.", ES, "¿Cuándo se construyó el muro?") == (1961, 0, 0)


def test_spanish_parts_are_worked_out_in_english():
    facts = ms.worked([("¿Cuándo llegó Colón a América?", "Colón llegó a América el 12 de octubre de 1492 [1]."),
                       ("¿Cuándo llegó el Mayflower?", "El Mayflower llegó en 1620 [2].")], ES)
    assert facts == ['October 12, 1492 ("¿Cuándo llegó Colón a América?") is earlier than 1620 ("¿Cuándo llegó el Mayflower?"), by 128 years.']
    # English alone doesn't read a Spanish date's month.
    assert ms.when("el 12 de octubre de 1492") == (1492, 0, 0)


def test_an_english_led_question_keeps_english_readings():
    for text in ("Darwin was born on February 12, 1809 [3].", "Cervantes died on 22 April 1616.", "Caesar died in 44 BC.",
                 "He lived to 969 years of age.", "Columbus reached the Americas in 1492 [3, 4]."):
        assert ms.when(text, BOTH, "When did it happen?") == ms.when(text)
        assert ms.first_sentence(text, BOTH) == ms.first_sentence(text)
    # Words that are English too ("fin") aren't Spanish event words for an English-led question.
    assert "fin" not in ms._event_words(BOTH)[1] and "fin" in ms._event_words(ES)[1]


def test_spanish_lists_and_patterns_are_yearfacts():
    src = (ms.language.KT / "YearFacts.kt").read_text(encoding="utf-8")
    assert ms.MESES[:12] == ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
                             "octubre", "noviembre", "diciembre"]
    assert len(ms.GROUPS_ES) == src[src.index("val GROUPS_ES"):src.index("val ENDS_ES")].count("setOf(")
    assert {"publico", "estreno"} <= ms.GROUPS_ES[0] and "cayo" in ms.ENDS_ES
    bc = re.search(r'const val BC_ES = "(.*)"', src).group(1).replace("\\\\", "\\")
    assert bc == ms.BC_ES
    rng = re.search(r'val RANGE_ES = Regex\("(.*)"\)', src).group(1).replace("\\\\", "\\").replace("$Y", ms._Y)
    assert rng == ms.RANGE_ES.pattern


def test_spanish_led_items_read_spanish_numbers():
    assert ms.amount("Tiene 1.500 habitantes.", ES) == (1500.0, "habitante", "habitantes")
    assert ms.amount("Mide 2,5 kilómetros.", ES) == (2.5, "kilómetro", "kilómetros")
    assert ms.amount("Unos 2.000.000,75 euros.", ES) == (2000000.75, "euro", "euros")
    assert ms.amount("Vivió 969 años.", ES) == (969.0, "año", "años")
    # Ambiguous "1,500" stays a thousand five hundred (as in Mexico); "1.5" a decimal.
    assert ms.amount("Tiene 1,500 habitantes.", ES) == (1500.0, "habitante", "habitantes")
    assert ms.amount("Mide 1.5 metros.", ES) == (1.5, "metro", "metros")
    facts = ms.worked([("¿Cuánto mide el puente A?", "Mide 2,5 kilómetros [1]."), ("¿Y el puente B?", "Mide 1,25 kilómetros [2].")], ES)
    assert facts == ['2.5 kilómetros ("¿Cuánto mide el puente A?") is more than 1.25 kilómetros ("¿Y el puente B?"), by 1.25 kilómetros.']


def test_english_and_english_led_items_read_english_numbers_only():
    for text in ("It has 1,500 people.", "It is 2.5 miles long.", "Mide 2,5 kilómetros.", "Tiene 1.500 habitantes."):
        assert ms.amount(text, BOTH) == ms.amount(text)
        assert ms.amount(text, ms.ENGLISH_ONLY) == ms.amount(text)
    assert ms.amount("Tiene 1.500 habitantes.") == (1.5, "habitante", "habitantes")
