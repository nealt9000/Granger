import shutil

import eval as cli
from conftest import FIXTURES


def test_report_worksheet_sweep_end_to_end(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(cli, "RUNS", tmp_path)
    monkeypatch.setattr(cli, "GRADES", FIXTURES / "grades.jsonl")
    run = tmp_path / "fx"
    run.mkdir()
    shutil.copy(FIXTURES / "dataset.json", run / "dataset.json")
    shutil.copy(FIXTURES / "results.jsonl", run / "results.jsonl")
    assert cli.main(["worksheet", "fx"]) == 0
    sheet = (run / "worksheet.md").read_text()
    assert "none-001" in sheet and "bible-001" not in sheet  # the Romans answer is already graded
    assert cli.main(["report", "fx"]) == 0 and "bible-001" in (run / "report.html").read_text()
    assert cli.main(["report", "fx", "--totals-only"]) == 0
    assert cli.main(["sweep", "fx", "--lexical", "0.20:0.50:0.10"]) == 0
    out = capsys.readouterr().out
    assert "0.50" in out
    assert cli.main(["validate", str(FIXTURES / "dataset.json")]) == 0
