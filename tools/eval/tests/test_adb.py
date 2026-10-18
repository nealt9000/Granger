import pytest

import adb

DUMPSYS = "  mResumedActivity: ActivityRecord{1a2b u0 %s/.Main t12}\n"


class Fake:
    def __init__(self, front="app.granger", done_after=2):
        self.calls, self.front, self.done_after, self.polls = [], front, done_after, 0

    def __call__(self, args):
        self.calls.append(args)
        if "dumpsys" in args:
            return 0, DUMPSYS % self.front
        if args[-2:-1] == ["ls"] or "ls" in args:
            self.polls += 1
            return (0, "done") if self.polls >= self.done_after else (1, "")
        return 0, ""


def test_refuses_when_another_app_is_in_front():
    with pytest.raises(SystemExit, match="com.whatsapp"):
        adb.check_front(Fake("com.whatsapp"))
    adb.check_front(Fake("app.granger"))
    adb.check_front(Fake("com.google.android.apps.nexuslauncher"))


def test_run_pulls_results_and_stops_on_done_marker(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")
    f = Fake()
    sleeps = []
    out = adb.run_eval(f, ds, "r1", tmp_path / "runs", ids="a,b", guesses="small", sleep=sleeps.append)
    cmds = [" ".join(c) for c in f.calls]
    assert any("push" in c and "/sdcard/Android/data/app.granger/files/eval/dataset.json" in c for c in cmds)
    start = [c for c in cmds if "am start" in c][0]
    assert "app.granger/.eval.EvalActivity" in start and "runId r1" in start and "ids a,b" in start and "guesses small" in start
    assert "groups" not in start
    assert any("pull" in c and "eval/r1/results.jsonl" in c for c in cmds)
    assert sleeps == [30]
    assert out == tmp_path / "runs" / "r1"
    assert (out / "dataset.json").exists()
