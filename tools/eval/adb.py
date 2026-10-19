"""Drives the on-device eval over adb. `runner(args) -> (returncode, stdout)` runs `adb <args>`; tests pass a fake.

The default runner calls the real adb. Nothing here is imported at module load that needs a device.
"""
import re
import shutil
import subprocess
import time
from pathlib import Path

PKG = "app.granger"
REMOTE = "/sdcard/Android/data/app.granger/files/eval"
POLL_SECONDS = 30
MAX_WAIT_SECONDS = 6 * 3600
MAX_ERRORS = 10


def real_runner(args):
    p = subprocess.run(["adb"] + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)  # stderr merged: adb shell ls reports there
    return p.returncode, p.stdout


ADB_ERRORS = ("device offline", "no devices", "device not found", "unauthorized", "no emulators")


def is_adb_error(output):
    low = output.strip().lower()
    return low.startswith("error:") or any(m in low for m in ADB_ERRORS)


RESUMED_LINE = re.compile(r"(?<![A-Za-z])(?:m|top)?ResumedActivity[:=]")  # mResumedActivity, topResumedActivity, ResumedActivity
RECORD_PKG = re.compile(r"ActivityRecord\{[^}]*?\s([A-Za-z0-9_.]+)/")


def front_package(runner):
    # Android 16 prints topResumedActivity=; multi-display prints several lines, the first is the top one
    _, out = runner(["shell", "dumpsys", "activity", "activities"])
    for line in out.splitlines():
        if RESUMED_LINE.search(line):
            m = RECORD_PKG.search(line)
            if m:
                return m.group(1)
    return None


def keyguard_showing(runner):
    _, out = runner(["shell", "dumpsys", "window"])
    return "isKeyguardShowing=true" in out


def check_front(runner):
    pkg = front_package(runner)
    if pkg is None:
        raise SystemExit("could not tell which app is in front (is the phone connected and awake?)")
    # The lock screen is System UI; the eval screen shows over it, and nobody is using an app behind it.
    if pkg == "com.android.systemui" and keyguard_showing(runner):
        return
    if pkg != PKG and "launcher" not in pkg:
        raise SystemExit("%s is in front on the phone; close it (or go to the home screen) before the eval starts" % pkg)


def run_eval(runner, dataset_path, run_id, runs_dir, ids=None, groups=None, guesses="none", fresh=False, sleep=time.sleep,
             max_wait=MAX_WAIT_SECONDS, max_errors=MAX_ERRORS):
    check_front(runner)
    runs_dir = Path(runs_dir)
    out = runs_dir / run_id
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(dataset_path, out / "dataset.json")
    runner(["shell", "mkdir", "-p", REMOTE])
    if fresh:
        runner(["shell", "rm", "-rf", "%s/%s" % (REMOTE, run_id)])  # start the run over
    else:
        # keep results.jsonl so the phone resumes after the last finished item; old markers must not end the wait early
        runner(["shell", "rm", "-f", "%s/%s/done" % (REMOTE, run_id), "%s/%s/failed" % (REMOTE, run_id)])
    rc, o = runner(["push", str(dataset_path), REMOTE + "/dataset.json"])
    if rc:
        raise SystemExit("adb push failed: " + o)
    # -S stops the app first, so the eval screen starts fresh with these extras (a screen left over from an install or
    # an earlier run would otherwise take the intent and never begin).
    start = ["shell", "am", "start", "-S", "-n", PKG + "/.eval.EvalActivity", "--es", "runId", run_id]
    for key, val in (("ids", ids), ("groups", groups), ("guesses", guesses)):
        if val:
            start += ["--es", key, val]
    rc, o = runner(start)
    if rc:
        raise SystemExit("could not start the eval activity: " + o)
    waited = errors = 0
    while True:
        rc, o = runner(["shell", "ls", "%s/%s/done" % (REMOTE, run_id)])
        if rc == 0:
            break
        if not is_adb_error(o):
            errors = 0  # any other failed ls just means the done marker is not there yet
            frc, fo = runner(["shell", "cat", "%s/%s/failed" % (REMOTE, run_id)])
            if frc == 0 and fo.strip() and "no such file" not in fo.lower() and not is_adb_error(fo):
                raise SystemExit("the eval failed on the phone: " + fo.strip())
        else:
            errors += 1
            if errors >= max_errors:
                raise SystemExit("adb failed %d times in a row while waiting for the eval: %s" % (errors, o.strip()))
        if waited >= max_wait:
            raise SystemExit("eval %s did not finish within %d seconds" % (run_id, max_wait))
        sleep(POLL_SECONDS)
        waited += POLL_SECONDS
    rc, o = runner(["pull", "%s/%s/results.jsonl" % (REMOTE, run_id), str(out) + "/"])
    # The search corpus (found.jsonl) is optional: older builds don't write it.
    runner(["pull", "%s/%s/found.jsonl" % (REMOTE, run_id), str(out) + "/"])
    if rc:
        raise SystemExit("adb pull failed: " + o)
    return out
