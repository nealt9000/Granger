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


def test_starts_over_the_lock_screen_but_not_over_the_open_shade():
    def phone(keyguard):
        def run(args):
            if args[-1] == "window":
                return 0, "    isKeyguardShowing=%s\n" % ("true" if keyguard else "false")
            return 0, DUMPSYS % "com.android.systemui"
        return run
    adb.check_front(phone(keyguard=True))
    with pytest.raises(SystemExit, match="systemui"):
        adb.check_front(phone(keyguard=False))


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
    rm = next(i for i, c in enumerate(cmds) if "rm -f" in c and "eval/r1/done" in c and "eval/r1/failed" in c)
    assert not any("rm -rf" in c for c in cmds)  # results.jsonl is kept so the phone can resume
    push = next(i for i, c in enumerate(cmds) if "push" in c)
    assert rm < push < next(i for i, c in enumerate(cmds) if "am start" in c)


def test_fresh_wipes_the_remote_run_directory(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")
    f = Fake()
    adb.run_eval(f, ds, "r1", tmp_path / "runs", fresh=True, sleep=lambda s: None)
    cmds = [" ".join(c) for c in f.calls]
    rm = next(i for i, c in enumerate(cmds) if "rm -rf" in c and "eval/r1" in c)
    assert rm < next(i for i, c in enumerate(cmds) if "push" in c) < next(i for i, c in enumerate(cmds) if "am start" in c)


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


def test_failed_marker_stops_the_wait_with_its_message(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")

    class Failing(Fake):
        def __call__(self, args):
            if "cat" in args:
                self.calls.append(args)
                return 0, "no dataset at /x/dataset.json\n"
            return super().__call__(args)

    f = Failing(done_after=99)
    with pytest.raises(SystemExit, match="no dataset at"):
        adb.run_eval(f, ds, "r1", tmp_path / "runs", sleep=lambda s: None)
    assert not any("pull" in " ".join(c) for c in f.calls)


def test_top_resumed_activity_format_is_read():
    # Android 16 (Pixel 6) prints topResumedActivity= instead of mResumedActivity:
    line = "    topResumedActivity=ActivityRecord{169309199 u0 app.granger/.MainActivity t44593}\n"
    adb.check_front(lambda args: (0, line) if "dumpsys" in args else (0, ""))


def test_top_resumed_activity_other_app_refuses_naming_package():
    line = "    topResumedActivity=ActivityRecord{1 u0 com.whatsapp/.Main t2}\n"
    with pytest.raises(SystemExit, match="com.whatsapp"):
        adb.check_front(lambda args: (0, line) if "dumpsys" in args else (0, ""))


def test_start_stops_the_app_first_so_the_screen_starts_fresh(tmp_path):
    ds = tmp_path / "d.json"
    ds.write_text("[]")
    fake = Fake()
    adb.run_eval(fake, ds, "r1", tmp_path / "runs", sleep=lambda s: None)
    start = next(c for c in fake.calls if "am" in c and "start" in c)
    assert "-S" in start and start[start.index("--es") + 1:start.index("--es") + 3] == ["runId", "r1"]
