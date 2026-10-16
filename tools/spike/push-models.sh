#!/usr/bin/env bash
# Pushes spike model files to the phone where SpikeActivity looks for them.
set -euo pipefail

MODELS_DIR="${MODELS_DIR:-$HOME/granger-models}"
PRIMARY=/sdcard/Android/data/app.granger/files/models
FALLBACK=/data/local/tmp/granger-models

files=(gemma-4-E2B-it.litertlm gemma-4-E2B-it-gpu.litertlm gemma-4-E4B-it.litertlm gemma-4-E4B-it-gpu.litertlm
       embeddinggemma-2-text-270m.litertlm)

target="${1:-$PRIMARY}"
[[ "$target" == "fallback" ]] && target=$FALLBACK

adb shell mkdir -p "$target"
for f in "${files[@]}"; do
  adb push "$MODELS_DIR/$f" "$target/$f"
done
[[ "$target" == "$FALLBACK" ]] && adb shell chmod -R a+rX "$FALLBACK"
adb shell ls -l "$target"
