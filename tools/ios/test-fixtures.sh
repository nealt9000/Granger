#!/bin/sh
# Plan I7 Task 6: the files the iPhone tests read, gathered into one folder outside the repo. The Simulator can't read
# every disk (an external volume, for one), so the tests take their fixtures from paths passed in TEST_RUNNER_GRANGER_*
# variables (tools/ios/test.sh sets them from this folder). Nothing here ships in the app; each download is a catalog
# item, checked against the catalog's sha256:
#   based.cooking.zim   a small Kiwix pack with a full-text index (Search, the pack reader, Best matches)
#   knots.zim           a nautilus book pack (A Library of Knots: Library collections, a pack's PDF)
#   epub/               Granger Classics' Pride and Prejudice (the e-book reader, read aloud)
#   pdf/field-notes.pdf a six-page PDF with an outline, made here by fixtures/make-test-pdf.swift
#   voices/…owl-medium  the Owl Piper voice, unpacked (read aloud)
#   audio/              a copy of testdata/audio (tags, chapters, gapless)
#   embedder/           EmbeddingGemma 2 (search by meaning), 165 MB
# About 230 MB in all. Run again to fill in anything missing; what's there and right is kept.
#
#     sh tools/ios/test-fixtures.sh ~/granger-fixtures
set -eu
DIR=${1:?usage: test-fixtures.sh <folder>}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$DIR/epub" "$DIR/pdf" "$DIR/voices" "$DIR/embedder"

get() { # get <file> <url> <sha256>
    if [ -f "$1" ] && [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$3" ]; then echo "have $(basename "$1")"; return; fi
    echo "fetching $(basename "$1")"
    curl -sfL -o "$1.part" "$2"
    GOT=$(shasum -a 256 "$1.part" | cut -d' ' -f1)
    if [ "$GOT" != "$3" ]; then echo "checksum mismatch for $2: $GOT" >&2; rm -f "$1.part"; exit 1; fi
    mv "$1.part" "$1"
}

get "$DIR/based.cooking.zim" https://download.kiwix.org/zim/zimit/based.cooking_en_all_2026-08.zim 52ca9fedc61da2fc846f71b1f74cf7fb8e96193fe9f671bb4632bcaf1181ce72
get "$DIR/knots.zim" https://download.kiwix.org/zim/other/zimgit-knots_en_2024-08.zim 7c3120b19fc2dc190b5ebfee01af54150136643d7b9d29d7fa9a6ba29787a57b
get "$DIR/epub/gc-pride-and-prejudice_2026-11-09.epub" https://github.com/nealt9000/Granger/releases/download/classics-2026-11-09/gc-pride-and-prejudice_2026-11-09.epub 397ae50744b8ba95278c7c272f2b7eb694038e0fbe65e5d0372851d20ecff718
get "$DIR/voices/owl.zip" https://github.com/nealt9000/Granger/releases/download/voices-v1/vits-piper-en_GB-granger_owl-medium.zip 6c508d0a82fa4807011bd65f183f09b9376488b8b586fec41c1ea41c40065d28
get "$DIR/embedder/embeddinggemma-2-text-270m.litertlm" https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm 2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb

if [ ! -d "$DIR/voices/vits-piper-en_GB-granger_owl-medium" ]; then
    unzip -q -o "$DIR/voices/owl.zip" -d "$DIR/voices"
fi
if [ ! -f "$DIR/pdf/field-notes.pdf" ]; then
    swift "$ROOT/tools/ios/fixtures/make-test-pdf.swift" "$DIR/pdf/field-notes.pdf"
fi
rm -rf "$DIR/audio"
cp -R "$ROOT/testdata/audio" "$DIR/audio"
echo "fixtures in $DIR"
