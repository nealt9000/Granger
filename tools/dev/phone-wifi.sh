#!/usr/bin/env bash
# Builds go to the Pixel over Wi-Fi (adb wireless debugging, Android 11+). See MAC-SETUP.md, "Wi-Fi installs".
#
#   tools/dev/phone-wifi.sh pair <ip:port> <code>   # once: the phone's "Pair device with pairing code" screen
#   tools/dev/phone-wifi.sh connect                 # finds the phone on the network and connects
#   tools/dev/phone-wifi.sh install                 # connect, then ./gradlew :app:installDebug onto it
#   tools/dev/phone-wifi.sh serial                  # prints the connected phone's serial (for ANDROID_SERIAL)
set -euo pipefail

cd "$(dirname "$0")/../.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

# The phone's connect address, from its mDNS advert (_adb-tls-connect), else an ip:port already connected.
serial() {
  local found
  found=$(adb mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF; exit}')
  if [[ -z "$found" ]]; then
    found=$(adb devices | awk '$2 == "device" && $1 ~ /:[0-9]+$|_adb-tls-connect/ {print $1; exit}')
  fi
  [[ -n "$found" ]] || { echo "No phone found on Wi-Fi. Is Wireless debugging on, on the same network?" >&2; exit 1; }
  echo "$found"
}

connect() {
  local target
  target=$(serial)
  # A target from mDNS may still need connecting; one already in `adb devices` is a no-op.
  adb connect "$target" >/dev/null 2>&1 || true
  adb -s "$target" get-state >/dev/null 2>&1 || { echo "Couldn't connect to $target" >&2; exit 1; }
  echo "$target"
}

case "${1:-}" in
  pair)
    [[ $# -eq 3 ]] || { echo "usage: $0 pair <ip:port> <code>" >&2; exit 2; }
    adb pair "$2" "$3"
    ;;
  connect)
    echo "Connected: $(connect)"
    ;;
  install)
    target=$(connect)
    echo "Installing onto $target"
    ANDROID_SERIAL="$target" ./gradlew :app:installDebug
    ;;
  serial)
    serial
    ;;
  *)
    sed -n '2,8p' "$0"
    exit 2
    ;;
esac
