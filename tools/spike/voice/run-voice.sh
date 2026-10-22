#!/usr/bin/env bash
# run-voice.sh <label> <am start extras...>
#
# One unattended VoiceSpikeActivity run on the phone: force-stop Granger, clear logcat, launch the activity with the
# extras, wait for its "Voice: auto done" line (or a crash), and save the Voice: lines to tools/spike/voice/<label>.log.
#
#   tools/spike/voice/run-voice.sh ljspeech-t2 --es engine vits-piper-en_US-ljspeech-medium --ei threads 2 --ei passes 2
#   tools/spike/voice/run-voice.sh ljspeech-t2-e2b --es engine vits-piper-en_US-ljspeech-medium --ei threads 2 --ei passes 2 --ez llm true
#
# Extras: engine (system | a voice directory's id under files/voices/ | all | comma-separated ids), threads, speaker,
# passes (2 = cold then warm in one process), llm (E2B resident on the GPU first), music (files/voices/music.* under
# the voice at 30 %). ANDROID_SERIAL picks the phone (tools/dev/phone-wifi.sh serial).
set -u
SERIAL="${ANDROID_SERIAL:-$(adb devices | awk '$2 == "device" {print $1; exit}')}"
OUT="$(cd "$(dirname "$0")" && pwd)"
label=$1; shift
adb -s "$SERIAL" shell am force-stop app.granger
sleep 2
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" logcat -c -b crash
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$SERIAL" shell am start -n app.granger/.voice.VoiceSpikeActivity "$@" >/dev/null
start=$(date +%s)
while true; do
  if adb -s "$SERIAL" logcat -d -s Granger AndroidRuntime | grep -q -E 'Voice: auto done|FATAL EXCEPTION'; then break; fi
  if adb -s "$SERIAL" logcat -d -b crash | grep -q -E 'Fatal signal'; then
    echo "NATIVE CRASH"; adb -s "$SERIAL" logcat -d -b crash | grep -E 'Fatal signal|Abort message' | cut -c1-300; break
  fi
  if [ $(( $(date +%s) - start )) -gt 1500 ]; then echo "TIMEOUT after 1500 s"; break; fi
  sleep 3
done
# logcat's main buffer can drop the first lines of a long run; the activity's own files/voice-spike-<time>.log has them all.
adb -s "$SERIAL" logcat -d -s Granger AndroidRuntime | grep -E 'Voice:|FATAL|Exception' > "$OUT/$label.log"
echo "== $label: $(wc -l < "$OUT/$label.log") lines"
grep -E 'summary|load |auto done|failed|FATAL|E2B resident|music' "$OUT/$label.log"
