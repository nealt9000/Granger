#!/bin/sh
# Plan I6 call 3 (Task 6): TagLib for the iPhone's MP3 tags. The tags spike found AVAsset reads every field Android
# uses from M4A/M4B, FLAC and Opus, but keeps only the first value of an ID3v2.4 multi-value text frame (TPE1
# "London Symphony Orchestra\0Gustav Holst" comes back as the orchestra alone), so MP3 tags go through TagLib, the same
# library and version Android reads them with. Built from the git submodule Android already uses
# (core/taglib/src/main/cpp/taglib, TagLib 2.3.2), so nothing is downloaded: a small static taglib.xcframework (iPhone
# and Simulator, arm64) into ios/Vendor (not in git). No zlib, bindings, tests or examples. LGPL-2.1/MPL-1.1 (the MPL
# allows the static link). Needs cmake. Run once after cloning (with the submodule checked out), and again when the
# submodule moves.
#
#     sh tools/ios/build-taglib.sh
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/core/taglib/src/main/cpp/taglib"
OUT="$ROOT/ios/Vendor"
STAMP="$OUT/taglib.version"
[ -f "$SRC/CMakeLists.txt" ] && [ -d "$SRC/3rdparty/utfcpp/source" ] || { echo "TagLib's submodule (and its utfcpp) isn't checked out: git submodule update --init --recursive core/taglib/src/main/cpp/taglib" >&2; exit 1; }
VERSION=$(git -C "$SRC" rev-parse HEAD)
# Bumped when what this script leaves in ios/Vendor changes, so an older build is redone.
LAYOUT="2"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION/$LAYOUT" ] && [ -d "$OUT/taglib.xcframework" ]; then
    echo "TagLib $VERSION already in ios/Vendor"
    exit 0
fi
command -v cmake >/dev/null || { echo "build-taglib needs cmake (brew install cmake)" >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

build() { # $1: iphoneos or iphonesimulator
    cmake -S "$SRC" -B "$TMP/build-$1" -G "Unix Makefiles" \
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$1" -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=26.0 \
        -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections" \
        -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF -DBUILD_BINDINGS=OFF -DWITH_ZLIB=OFF \
        >"$TMP/cmake-$1.log" 2>&1 || { tail -40 "$TMP/cmake-$1.log" >&2; exit 1; }
    cmake --build "$TMP/build-$1" --target tag -j 8 >"$TMP/build-$1.log" 2>&1 || { tail -40 "$TMP/build-$1.log" >&2; exit 1; }
    mkdir -p "$TMP/lib-$1"
    cp "$(find "$TMP/build-$1" -name 'libtag.a' | head -1)" "$TMP/lib-$1/libtag.a"
}
build iphoneos
build iphonesimulator
# The public headers (and the templates they include), under taglib/: `#include <taglib/fileref.h>`.
mkdir -p "$TMP/include/taglib"
find "$SRC/taglib" \( -name '*.h' -o -name '*.tcc' \) ! -path '*/3rdparty/*' -exec cp {} "$TMP/include/taglib/" \;
cp "$TMP/build-iphoneos/taglib_config.h" "$TMP/include/taglib/" 2>/dev/null || true
find "$TMP/build-iphoneos" -name 'taglib_export.h' -exec cp {} "$TMP/include/taglib/" \;
find "$TMP/build-iphoneos" -name 'config.h' -maxdepth 1 -exec cp {} "$TMP/include/taglib/" \;
mkdir -p "$OUT"
rm -rf "$OUT/taglib.xcframework"
xcodebuild -create-xcframework \
    -library "$TMP/lib-iphoneos/libtag.a" -headers "$TMP/include" \
    -library "$TMP/lib-iphonesimulator/libtag.a" -headers "$TMP/include" \
    -output "$OUT/taglib.xcframework" >/dev/null
echo "$VERSION/$LAYOUT" > "$STAMP"
echo "TagLib $VERSION in ios/Vendor"
