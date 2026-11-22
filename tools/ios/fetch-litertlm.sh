#!/bin/sh
# Plan I7 call 6 (Task 5): LiteRT-LM 0.18.0's embedding engine for search by meaning on the iPhone, the same release as
# Android's litertlm-android (gradle/libs.versions.toml), so EmbeddingGemma 2 (.litertlm) gives the iPhone the query
# vectors Android gets and the published .gvec files work unchanged. Google publishes a prebuilt CLiteRTLM.xcframework
# with each release (one dynamic framework, device arm64 and Simulator arm64, its C API as a Clang module); this fetches
# it, checks its sha256 and unpacks it to ios/Vendor/CLiteRTLM.xcframework (not in git: about 55 MB a slice). The app
# embeds it. Apache-2.0, with its third-party notices inside the framework. Run once after cloning, and again when
# VERSION changes.
#
#     sh tools/ios/fetch-litertlm.sh
set -eu
VERSION="0.18.0"
# Bumped when what this script leaves in ios/Vendor changes, so an older unpack is redone.
LAYOUT="1"
SHA256="d765b99592d4ec3d0c9e2bd69469454af06c834861340672da1891c0c121c347"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/ios/Vendor"
STAMP="$OUT/CLiteRTLM.version"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION/$LAYOUT" ] && [ -d "$OUT/CLiteRTLM.xcframework" ]; then
    echo "LiteRT-LM $VERSION already in ios/Vendor"
    exit 0
fi
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -sfL -o "$TMP/CLiteRTLM.xcframework.zip" "https://github.com/google-ai-edge/LiteRT-LM/releases/download/v$VERSION/CLiteRTLM.xcframework.zip"
GOT=$(shasum -a 256 "$TMP/CLiteRTLM.xcframework.zip" | cut -d' ' -f1)
if [ "$GOT" != "$SHA256" ]; then
    echo "LiteRT-LM checksum mismatch: $GOT" >&2
    exit 1
fi
unzip -q "$TMP/CLiteRTLM.xcframework.zip" -d "$TMP/x"
mkdir -p "$OUT"
rm -rf "$OUT/CLiteRTLM.xcframework"
mv "$TMP/x/CLiteRTLM.xcframework" "$OUT/CLiteRTLM.xcframework"
echo "$VERSION/$LAYOUT" > "$STAMP"
echo "LiteRT-LM $VERSION in ios/Vendor"
