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
            return (0, "done") if self.polls >= self.done_after else (1, "ls: x/done: No such file or directory")
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


def test_stale_remote_dir_is_removed_before_start(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")
    f = Fake()
    adb.run_eval(f, ds, "r1", tmp_path / "runs", sleep=lambda s: None)
    cmds = [" ".join(c) for c in f.calls]
    rm = next(i for i, c in enumerate(cmds) if "rm -rf" in c and "eval/r1" in c)
    assert rm < next(i for i, c in enumerate(cmds) if "am start" in c)


def test_times_out_when_never_done(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")
    with pytest.raises(SystemExit, match="did not finish"):
        adb.run_eval(Fake(done_after=10**9), ds, "r1", tmp_path / "runs", sleep=lambda s: None, max_wait=90)


def test_aborts_after_consecutive_adb_errors(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")

    class Dead(Fake):
        def __call__(self, args):
            if "ls" in args:
                return 1, "error: device offline"
            return super().__call__(args)

    with pytest.raises(SystemExit, match="3 times in a row"):
        adb.run_eval(Dead(), ds, "r1", tmp_path / "runs", sleep=lambda s: None, max_errors=3)


def test_missing_marker_with_empty_output_is_not_an_error(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")

    class Quiet(Fake):
        def __call__(self, args):
            r = super().__call__(args)
            return (1, "") if r[0] == 1 else r

    f = Quiet(done_after=15)  # more polls than max_errors
    adb.run_eval(f, ds, "r1", tmp_path / "runs", sleep=lambda s: None, max_errors=3)
    assert f.polls == 15


def test_no_devices_error_aborts(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")

    class NoDev(Fake):
        def __call__(self, args):
            if "ls" in args:
                return 1, "error: no devices/emulators found"
            return super().__call__(args)

    with pytest.raises(SystemExit, match="2 times in a row"):
        adb.run_eval(NoDev(), ds, "r1", tmp_path / "runs", sleep=lambda s: None, max_errors=2)
