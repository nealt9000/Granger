#!/usr/bin/env bash
# Sets up the trainer on a rented Linux GPU box (Plan V3, round 2): piper1-gpl at the README's pin in <work>/venv.
#
#   bash pod_setup.sh <work>          # e.g. /workspace/voice-train (a volume that survives a pod restart)
#
# Then copy the dataset, the warm-start checkpoint and tools/voices/train/ in (README.md, "Round 2") and run train.sh
# under tmux with --trainer.accelerator gpu. Linux has no macOS path-length trap, so espeak-ng builds in place.
set -euo pipefail

work="$1"
pin=619f513
mkdir -p "$work/src"

if command -v apt-get >/dev/null; then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git cmake ninja-build build-essential tmux rsync python3.11-venv python3.11-dev >/dev/null
fi

if [ ! -d "$work/src/piper1-gpl" ]; then
    git clone -q https://github.com/OHF-Voice/piper1-gpl.git "$work/src/piper1-gpl"
fi
git -C "$work/src/piper1-gpl" checkout -q "$pin"

[ -x "$work/venv/bin/python" ] || python3.11 -m venv "$work/venv"
"$work/venv/bin/pip" install -q --upgrade pip
"$work/venv/bin/pip" install -q -e "$work/src/piper1-gpl[train]" scikit-build cython

# monotonic alignment, as build_monotonic_align.sh does
ma="$work/src/piper1-gpl/src/piper/train/vits/monotonic_align"
mkdir -p "$ma/monotonic_align"
(cd "$ma" && "$work/venv/bin/cythonize" -q -i core.pyx && mv core*.so monotonic_align/)

# the espeak-ng bridge
(cd "$work/src/piper1-gpl" && "$work/venv/bin/python" setup.py -q build_ext --inplace)

"$work/venv/bin/python" - <<'EOF'
import torch
from piper import espeakbridge  # noqa: F401
from piper.train.vits.monotonic_align.monotonic_align import core  # noqa: F401
print("torch", torch.__version__, "cuda", torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else "")
EOF
