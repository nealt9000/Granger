import pytest

import results

GOOD = '{"id": "a", "outcome": "answered"}'


def test_loads_good_lines(tmp_path):
    p = tmp_path / "results.jsonl"
    p.write_text(GOOD + "\n\n" + GOOD + "\n")
    assert len(results.load_results(p)) == 2


def test_truncated_last_line_is_skipped_with_a_warning(tmp_path, capsys):
    p = tmp_path / "results.jsonl"
    p.write_text(GOOD + '\n{"id": "b", "outc')
    assert len(results.load_results(p)) == 1
    assert "results.jsonl:2" in capsys.readouterr().err


def test_bad_middle_line_names_file_and_line(tmp_path):
    p = tmp_path / "results.jsonl"
    p.write_text(GOOD + "\n{oops\n" + GOOD + "\n")
    with pytest.raises(SystemExit, match=r"results.jsonl:2"):
        results.load_results(p)


def test_record_without_outcome_is_rejected(tmp_path):
    p = tmp_path / "results.jsonl"
    p.write_text(GOOD + '\n{"id": "b", "outcome": "maybe"}\n' + GOOD + "\n")
    with pytest.raises(SystemExit, match=r"results.jsonl:2"):
        results.load_results(p)
