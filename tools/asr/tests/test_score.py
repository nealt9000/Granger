import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import score  # noqa: E402


def test_numbers_match_as_cardinals_or_years():
    assert score.errors("How many years after ten sixty six?", "How many years after 1066")[0] == 0
    assert score.errors("It is eight thousand eight hundred forty nine metres", "It is 8849 metres")[0] == 0
    assert score.errors("In twenty twenty six", "In 2026")[0] == 0


def test_punctuation_case_and_apostrophes_are_ignored():
    assert score.errors("What's Adam Smith's example?", "whats adam smiths example")[0] == 0
    assert score.errors("Who was Scrooge's business partner?", "who was scrooges business partner") == (0, 5)


def test_word_errors_are_counted():
    assert score.errors("What were the main causes of the fall", "What were the main courses of the fall") == (1, 8)


def test_name_hits_exact_and_with_spaces_ignored():
    assert score.name_hit("Wikivoyage", [], "Open with the Voyage") == (False, False)
    assert score.name_hit("Wikivoyage", [], "open Wiki voyage") == (False, True)
    assert score.name_hit("Ready.gov", ["ready gov"], "Open ready gov") == (True, True)
    assert score.name_hit("The Mayor of Casterbridge", ["mayor of casterbridge"], "open the mayor of Casterbridge please") == (True, True)
