"""Drives the on-device eval over adb. `runner(args) -> (returncode, stdout)` runs `adb <args>`; tests pass a fake.

The default runner calls the real adb. Nothing here is imported at module load that needs a device.
"""
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
    p = subprocess.run(["adb"] + list(args), capture_output=True, text=True)
    return p.returncode, p.stdout


def front_package(runner):
    _, out = runner(["shell", "dumpsys", "activity", "activities"])
    for line in out.splitlines():
        if "mResumedActivity" in line:
            for tok in line.split():
                if "/" in tok:
                    return tok.split("/")[0].lstrip("{")
    return None


def check_front(runner):
    pkg = front_package(runner)
    if pkg is None:
        raise SystemExit("could not tell which app is in front (is the phone connected and awake?)")
    if pkg != PKG and "launcher" not in pkg:
        raise SystemExit("%s is in front on the phone; close it (or go to the home screen) before the eval starts" % pkg)


def run_eval(runner, dataset_path, run_id, runs_dir, ids=None, groups=None, guesses="none", sleep=time.sleep,
             max_wait=MAX_WAIT_SECONDS, max_errors=MAX_ERRORS):
    check_front(runner)
    runs_dir = Path(runs_dir)
    out = runs_dir / run_id
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(dataset_path, out / "dataset.json")
    runner(["shell", "mkdir", "-p", REMOTE])
    runner(["shell", "rm", "-rf", "%s/%s" % (REMOTE, run_id)])  # a reused run id must not see an old done marker
    rc, o = runner(["push", str(dataset_path), REMOTE + "/dataset.json"])
    if rc:
        raise SystemExit("adb push failed: " + o)
    start = ["shell", "am", "start", "-n", PKG + "/.eval.EvalActivity", "--es", "runId", run_id]
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
        if "No such file" in o:
            errors = 0
        else:
            errors += 1
            if errors >= max_errors:
                raise SystemExit("adb failed %d times in a row while waiting for the eval: %s" % (errors, o.strip()))
        if waited >= max_wait:
            raise SystemExit("eval %s did not finish within %d seconds" % (run_id, max_wait))
        sleep(POLL_SECONDS)
        waited += POLL_SECONDS
    rc, o = runner(["pull", "%s/%s/results.jsonl" % (REMOTE, run_id), str(out) + "/"])
    if rc:
        raise SystemExit("adb pull failed: " + o)
    return out
