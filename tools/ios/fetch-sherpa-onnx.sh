#!/bin/sh
# Plan I5 call 3 (Task 8): sherpa-onnx 1.13.8 for the Piper voices on the iPhone, the same release as Android's vendored
# AAR. sherpa-onnx publishes prebuilt Apple frameworks under its `xcframework` release tag; this fetches the iOS one
# with onnxruntime linked in statically (one dynamic SherpaOnnxC.framework, device arm64 and Simulator arm64/x86_64, its
# C API as a Clang module), checks its sha256 and unpacks it to ios/Vendor/SherpaOnnxC.xcframework (not in git: 60 MB a
# slice). The app embeds it. Apache-2.0 (sherpa-onnx) and MIT (onnxruntime). Run once after cloning, and again when
# VERSION changes.
#
#     sh tools/ios/fetch-sherpa-onnx.sh
set -eu
VERSION="1.13.8"
# Bumped when what this script leaves in ios/Vendor changes, so an older unpack is redone.
LAYOUT="1"
SHA256="e259a7d3b38ad7dec49bb078252a30bb42ede8355e2bb130cf8c1c78ed131f75"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/ios/Vendor"
STAMP="$OUT/SherpaOnnxC.version"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION/$LAYOUT" ] && [ -d "$OUT/SherpaOnnxC.xcframework" ]; then
    echo "sherpa-onnx $VERSION already in ios/Vendor"
    exit 0
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
NAME="sherpa-onnx-v$VERSION-ios-shared-onnxruntime-static.xcframework"
curl -sfL -o "$TMP/$NAME.zip" "https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/$NAME.zip"
GOT=$(shasum -a 256 "$TMP/$NAME.zip" | cut -d' ' -f1)
if [ "$GOT" != "$SHA256" ]; then
    echo "sherpa-onnx checksum mismatch: $GOT" >&2
    exit 1
fi
unzip -q "$TMP/$NAME.zip" -d "$TMP/x"
mkdir -p "$OUT"
rm -rf "$OUT/SherpaOnnxC.xcframework"
mv "$TMP/x/SherpaOnnxC.xcframework" "$OUT/SherpaOnnxC.xcframework"
echo "$VERSION/$LAYOUT" > "$STAMP"
echo "sherpa-onnx $VERSION in ios/Vendor"
