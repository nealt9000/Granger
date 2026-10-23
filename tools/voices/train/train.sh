#!/usr/bin/env bash
# Fine-tunes the Granger voice from Piper's ljspeech-medium checkpoint (Plan V3).
#
#   tools/voices/train/train.sh <work> <dataset name> <run name> <num speakers> [extra LightningCLI args…]
#
# Expects fetch.py's <work>/base and build_dataset.py's <work>/dataset/<dataset name>/{wav,metadata.csv}; writes the
# phoneme/audio cache to <work>/cache/<dataset name>, the voice config to <work>/dataset/<dataset name>/config.json,
# and checkpoints and logs to <work>/runs/<run name>. PIPER_PYTHON is the training venv's python (README.md).
#
# The first run warm-starts from lj-med_1000.ckpt: every parameter whose shape matches is copied (all of them for one
# speaker; all but the new speaker embedding and conditioning layers for several), with a fresh optimizer. Run it
# again with the same run name to resume from last.ckpt; piper1-gpl skips the warm-start on a resume.
# GRANGER_WARMSTART names another checkpoint to warm-start from (round 2: widen_speakers.py's output).
#
# Extra args go to LightningCLI, e.g. --trainer.max_epochs 1200 --trainer.accelerator mps --data.batch_size 16.
set -euo pipefail

work="$1"; dataset="$2"; run="$3"; speakers="$4"; shift 4
python="${PIPER_PYTHON:?set PIPER_PYTHON to the training venv python}"
data="$work/dataset/$dataset"
root="$work/runs/$run"
mkdir -p "$root"

resume=()
# Each resume logs to a new lightning_logs/version_N: take the newest last.ckpt by time (version_10 sorts before _9).
# -L: runs/ may be a link to a volume; -exec … + runs no ls when nothing matches (GNU xargs would run a bare ls).
last="$(find -L "$root" -name last.ckpt -exec ls -t {} + 2>/dev/null | head -1)"
if [ -n "$last" ]; then
    echo "resuming from $last" >&2
    resume=(--ckpt_path "$last")
else
    resume=(--model.warmstart_ckpt "${GRANGER_WARMSTART:-$work/base/lj-med_1000.ckpt}")
fi

exec "$python" "$(dirname "$0")/fit.py" fit \
    --data.voice_name granger_libritts \
    --data.csv_path "$data/metadata.csv" \
    --data.audio_dir "$data/wav" \
    --data.espeak_voice en-us \
    --data.cache_dir "$work/cache/$dataset" \
    --data.config_path "$data/config.json" \
    --data.batch_size 32 \
    --model.sample_rate 22050 \
    --model.num_speakers "$speakers" \
    --model.lr_decay 0.999875 \
    --model.lr_decay_d 0.999875 \
    --model.mos_metric none \
    --trainer.default_root_dir "$root" \
    --trainer.max_epochs 2000 \
    "${resume[@]}" \
    "$@"
