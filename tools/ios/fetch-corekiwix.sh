#!/bin/sh
# Plan I4 Task 5: Kiwix's prebuilt CoreKiwix.xcframework (libkiwix 14.2.1, the line Android's java-libkiwix 2.6.0 uses),
# fetched from download.kiwix.org, checked against the sha256 below, unpacked to ios/Vendor/CoreKiwix.xcframework (not in
# git: 130 MB of static libraries). The app target links it; run this once after cloning, and again when VERSION changes.
#
#     sh tools/ios/fetch-corekiwix.sh
set -eu
VERSION="14.2.1-2"
# Bumped when what this script leaves in ios/Vendor changes, so an older unpack is redone.
LAYOUT="2"
SHA256="14820648788dddc3df8a89ff0820af60d7efc1cfda6c83602989455263ee1561"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/ios/Vendor"
STAMP="$OUT/CoreKiwix.version"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION/$LAYOUT" ] && [ -d "$OUT/CoreKiwix.xcframework" ]; then
    echo "CoreKiwix $VERSION already in ios/Vendor"
    exit 0
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
NAME="libkiwix_xcframework-$VERSION"
curl -sfL -o "$TMP/$NAME.tar.gz" "https://download.kiwix.org/release/libkiwix/$NAME.tar.gz"
GOT=$(shasum -a 256 "$TMP/$NAME.tar.gz" | cut -d' ' -f1)
if [ "$GOT" != "$SHA256" ]; then
    echo "CoreKiwix checksum mismatch: $GOT" >&2
    exit 1
fi
tar xzf "$TMP/$NAME.tar.gz" -C "$TMP"
mkdir -p "$OUT"
rm -rf "$OUT/CoreKiwix.xcframework"
mv "$TMP/$NAME/lib/CoreKiwix.xcframework" "$OUT/CoreKiwix.xcframework"
# Plan I5 Task 5: Xcode copies a static xcframework's headers into the build's shared include folder, where CoreKiwix's
# own zlib.h and zconf.h shadow the SDK's for every target. Readium's Minizip then defines gzFile_s differently from
# the SDK's zlib module and the build fails. No CoreKiwix header includes them and the app links the SDK's libz, so
# they go.
find "$OUT/CoreKiwix.xcframework" -path '*/Headers/zlib.h' -delete
find "$OUT/CoreKiwix.xcframework" -path '*/Headers/zconf.h' -delete
echo "$VERSION/$LAYOUT" > "$STAMP"
echo "CoreKiwix $VERSION in ios/Vendor"
