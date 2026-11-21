#!/bin/sh
# Plan I5 call 6 (Task 6): libarchive for reading CBR comics (RAR 4) on the iPhone, where Android uses junrar. iOS has no
# public libarchive, so this fetches a pinned source release, checks its sha256, and builds a small static
# libarchive.xcframework (iPhone and Simulator, arm64) into ios/Vendor (not in git). Nothing but libarchive itself:
# no OpenSSL, no lzma, zstd, lz4, bzip2 or zlib (RAR needs none), no tools or tests. The app calls only the RAR reader,
# so the linker keeps little else. 3.8.1 carries the 2025 RAR reader fixes. BSD-2 licence. Needs cmake. Run once after
# cloning, and again when VERSION changes.
#
#     sh tools/ios/fetch-libarchive.sh
set -eu
VERSION="3.8.1"
# Bumped when what this script leaves in ios/Vendor changes, so an older build is redone.
LAYOUT="1"
SHA256="19f917d42d530f98815ac824d90c7eaf648e9d9a50e4f309c812457ffa5496b5"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/ios/Vendor"
STAMP="$OUT/libarchive.version"
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION/$LAYOUT" ] && [ -d "$OUT/libarchive.xcframework" ]; then
    echo "libarchive $VERSION already in ios/Vendor"
    exit 0
fi
command -v cmake >/dev/null || { echo "fetch-libarchive needs cmake (brew install cmake)" >&2; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
NAME="libarchive-$VERSION"
curl -sfL -o "$TMP/$NAME.tar.xz" "https://github.com/libarchive/libarchive/releases/download/v$VERSION/$NAME.tar.xz"
GOT=$(shasum -a 256 "$TMP/$NAME.tar.xz" | cut -d' ' -f1)
if [ "$GOT" != "$SHA256" ]; then
    echo "libarchive checksum mismatch: $GOT" >&2
    exit 1
fi
tar xJf "$TMP/$NAME.tar.xz" -C "$TMP"

build() { # $1: iphoneos or iphonesimulator
    cmake -S "$TMP/$NAME" -B "$TMP/build-$1" -G "Unix Makefiles" \
        -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$1" -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=26.0 \
        -DCMAKE_BUILD_TYPE=MinSizeRel -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" \
        -DBUILD_SHARED_LIBS=OFF -DENABLE_TEST=OFF -DENABLE_INSTALL=OFF -DENABLE_WERROR=OFF \
        -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF \
        -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF -DENABLE_CNG=OFF -DENABLE_LIBB2=OFF \
        -DENABLE_LZ4=OFF -DENABLE_LZO=OFF -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF -DENABLE_BZip2=OFF -DENABLE_ZLIB=OFF \
        -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF -DENABLE_ICONV=OFF \
        -DENABLE_ACL=OFF -DENABLE_XATTR=OFF >"$TMP/cmake-$1.log" 2>&1 || { tail -40 "$TMP/cmake-$1.log" >&2; exit 1; }
    cmake --build "$TMP/build-$1" --target archive_static -j 8 >"$TMP/build-$1.log" 2>&1 || { tail -40 "$TMP/build-$1.log" >&2; exit 1; }
    mkdir -p "$TMP/lib-$1"
    cp "$(find "$TMP/build-$1" -name 'libarchive.a' | head -1)" "$TMP/lib-$1/libarchive.a"
}
build iphoneos
build iphonesimulator
mkdir -p "$TMP/include"
cp "$TMP/$NAME/libarchive/archive.h" "$TMP/$NAME/libarchive/archive_entry.h" "$TMP/include/"
mkdir -p "$OUT"
rm -rf "$OUT/libarchive.xcframework"
xcodebuild -create-xcframework \
    -library "$TMP/lib-iphoneos/libarchive.a" -headers "$TMP/include" \
    -library "$TMP/lib-iphonesimulator/libarchive.a" -headers "$TMP/include" \
    -output "$OUT/libarchive.xcframework" >/dev/null
echo "$VERSION/$LAYOUT" > "$STAMP"
echo "libarchive $VERSION in ios/Vendor"
