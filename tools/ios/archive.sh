#!/bin/sh
# Archives the iPhone app for App Store Connect (Plan I8 calls 6 and 7), and with --upload sends it to TestFlight.
#
#     sh tools/ios/archive.sh --config Friends            # the friends' build (TestFlight)
#     sh tools/ios/archive.sh --config Release --upload   # the App Store's build, uploaded
#
# 1. Checks your Apple team is in ios/Local.xcconfig (DEVELOPMENT_TEAM; see ios/Local.xcconfig.example).
# 2. Checks the released core is pinned in ios/GrangerCore/Package.swift (no GRANGER_CORE_LOCAL) and that no Kotlin under
#    core/ or shared/ changed since the commit that pinned it; otherwise run tools/ios/release-core.sh --publish first.
# 3. Sets the version as Android's: 0.1.<commit count> and the commit count as the build, through an xcconfig of its own
#    in build/ios-archive/ (the committed project keeps 0.1 (1)). Run it on main, so both phones share the number.
# 4. Archives and exports for App Store Connect into build/ios-archive/.
# 5. With --upload: sends the build with an App Store Connect API key kept in ~/.config/granger/ (never in git):
#      ~/.config/granger/appstoreconnect.properties   keyId=ABC123DEFG
#                                                    issuerId=00000000-0000-0000-0000-000000000000
#      ~/.config/granger/AuthKey_<keyId>.p8           the key App Store Connect gives you once (Users and Access ›
#                                                    Integrations › App Store Connect API, role App Manager)
#    Without a key it prints Xcode Organizer's steps for the archive instead.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CONFIG=""
UPLOAD=0
usage() { echo "usage: archive.sh --config Friends|Release [--upload]" >&2; exit 2; }
while [ $# -gt 0 ]; do
    case "$1" in
        --config) [ $# -ge 2 ] || usage; CONFIG=$2; shift 2 ;;
        --upload) UPLOAD=1; shift ;;
        *) usage ;;
    esac
done
case "$CONFIG" in Friends|Release) ;; *) usage ;; esac
fail() { echo "archive.sh: $*" >&2; exit 1; }

# 1. The team.
LOCAL="$ROOT/ios/Local.xcconfig"
[ -f "$LOCAL" ] || fail "no ios/Local.xcconfig. Copy ios/Local.xcconfig.example there and set DEVELOPMENT_TEAM to your Apple team's ID (Xcode › Settings › Accounts, or developer.apple.com › Membership)."
TEAM=$(sed -n 's/^[[:space:]]*DEVELOPMENT_TEAM[[:space:]]*=[[:space:]]*\([A-Z0-9]*\).*/\1/p' "$LOCAL" | tail -1)
[ -n "$TEAM" ] || fail "ios/Local.xcconfig sets no DEVELOPMENT_TEAM: add your Apple team's ID (10 letters and digits)."
[ "$TEAM" != "ABCDE12345" ] || fail "ios/Local.xcconfig still has the example team ABCDE12345: set your own."

# 2. The released core.
[ "${GRANGER_CORE_LOCAL:-}" != "1" ] || fail "GRANGER_CORE_LOCAL=1 is set: an archive uses the released core. Unset it."
PKG="$ROOT/ios/GrangerCore/Package.swift"
URL=$(sed -n 's/^let releaseURL = "\(.*\)"/\1/p' "$PKG")
[ -n "$URL" ] || fail "no core release is pinned in ios/GrangerCore/Package.swift: run tools/ios/release-core.sh --publish."
PINNED=$(cd "$ROOT" && git log -1 --format=%H -G '^let releaseURL' -- ios/GrangerCore/Package.swift)
MOVED=$(cd "$ROOT" && git log --oneline "$PINNED"..HEAD -- core shared | head -5)
[ -z "$MOVED" ] || fail "Kotlin changed since the pinned core ($(cd "$ROOT" && git log -1 --format=%h "$PINNED")), so the release doesn't match this commit:
$MOVED
Run tools/ios/release-core.sh --publish, commit the pin, then archive."
DIRTY=$(cd "$ROOT" && git status --porcelain -- ios core shared)
[ -z "$DIRTY" ] || echo "archive.sh: note: uncommitted changes under ios/, core/ or shared/ go into this archive." >&2

# 3. The version.
COUNT=$(cd "$ROOT" && git rev-list --count HEAD)
VERSION="0.1.$COUNT"
if (cd "$ROOT" && git rev-parse -q --verify origin/main >/dev/null) && ! (cd "$ROOT" && git merge-base --is-ancestor HEAD origin/main); then
    echo "archive.sh: note: HEAD isn't on origin/main, so $VERSION may not match the Android build of the same number." >&2
fi
OUT="$ROOT/build/ios-archive"
mkdir -p "$OUT"
XCCONFIG="$OUT/Version.xcconfig"
printf '// Written by tools/ios/archive.sh for this archive (Plan I8 call 6).\nMARKETING_VERSION = %s\nCURRENT_PROJECT_VERSION = %s\n' "$VERSION" "$COUNT" > "$XCCONFIG"
ARCHIVE="$OUT/Granger-$CONFIG-$VERSION.xcarchive"
echo "Archiving Granger $VERSION ($COUNT), $CONFIG, team $TEAM"

# 4. Archive and export.
rm -rf "$ARCHIVE"
(cd "$ROOT/ios/Granger" && xcodebuild archive -project Granger.xcodeproj -scheme Granger -configuration "$CONFIG" \
    -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" -xcconfig "$XCCONFIG" -allowProvisioningUpdates)

PROPS="$HOME/.config/granger/appstoreconnect.properties"
KEY_ID=""; ISSUER=""; KEY=""
if [ -f "$PROPS" ]; then
    KEY_ID=$(sed -n 's/^keyId=\(.*\)/\1/p' "$PROPS" | tr -d '[:space:]')
    ISSUER=$(sed -n 's/^issuerId=\(.*\)/\1/p' "$PROPS" | tr -d '[:space:]')
    KEY="$HOME/.config/granger/AuthKey_$KEY_ID.p8"
fi
DEST=export
[ "$UPLOAD" -eq 1 ] && [ -n "$KEY_ID" ] && [ -n "$ISSUER" ] && [ -f "$KEY" ] && DEST=upload
OPTIONS="$OUT/ExportOptions-$DEST.plist"
cat > "$OPTIONS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key><string>app-store-connect</string>
    <key>destination</key><string>$DEST</string>
    <key>teamID</key><string>$TEAM</string>
    <key>signingStyle</key><string>automatic</string>
    <key>uploadSymbols</key><true/>
    <key>manageAppVersionAndBuildNumber</key><false/>
</dict>
</plist>
EOF
EXPORT="$OUT/export-$CONFIG-$VERSION"
rm -rf "$EXPORT"
if [ "$DEST" = upload ]; then
    (cd "$ROOT/ios/Granger" && xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$OPTIONS" -exportPath "$EXPORT" \
        -allowProvisioningUpdates -authenticationKeyPath "$KEY" -authenticationKeyID "$KEY_ID" -authenticationKeyIssuerID "$ISSUER")
    echo "Uploaded Granger $VERSION ($COUNT), $CONFIG. App Store Connect processes it in a few minutes; its email lists anything to fix (read the privacy report, ITMS-91053)."
    exit 0
fi
(cd "$ROOT/ios/Granger" && xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$OPTIONS" -exportPath "$EXPORT" -allowProvisioningUpdates)
echo "Archive: $ARCHIVE"
echo "Exported: $EXPORT"
if [ "$UPLOAD" -eq 1 ]; then
    echo "No App Store Connect API key in ~/.config/granger/ (appstoreconnect.properties with keyId= and issuerId=, and AuthKey_<keyId>.p8)."
fi
cat <<EOF
To upload from Xcode instead:
  1. open "$ARCHIVE"   (Xcode's Organizer opens on it)
  2. Distribute App › App Store Connect › Upload
  3. Keep the defaults (automatic signing, upload symbols) and press Upload.
EOF
