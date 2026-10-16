#!/usr/bin/env bash
# Downloads a small real Wikipedia ZIM (verified), then pushes it and the test ZIM to the phone's
# pack folder, where the Library screen finds them on its next resume.
set -euo pipefail
cd "$(dirname "$0")/../.."

PACKS_DIR="${PACKS_DIR:-$HOME/granger-packs}"
TARGET=/sdcard/Android/data/app.granger/files/packs
NAME=wikipedia_en_ray-charles_maxi_2026-08.zim
SHA256=74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976

mkdir -p "$PACKS_DIR"
if [[ ! -f "$PACKS_DIR/$NAME" ]]; then
  curl -L --fail -o "$PACKS_DIR/$NAME.part" "https://download.kiwix.org/zim/wikipedia/$NAME"
  mv "$PACKS_DIR/$NAME.part" "$PACKS_DIR/$NAME"
fi
echo "$SHA256  $PACKS_DIR/$NAME" | sha256sum -c -

# Let the app create its own pack folder: a folder made with `adb shell mkdir` belongs to the shell
# user and the app can't list it. (Requires the app to be installed: ./gradlew :app:installDebug.)
adb shell am start -W -n app.granger/.MainActivity >/dev/null
adb shell ls -d "$TARGET" >/dev/null

adb push "$PACKS_DIR/$NAME" "$TARGET/$NAME"
adb push testdata/granger-test.zim "$TARGET/granger-test.zim"
adb shell ls -l "$TARGET"
