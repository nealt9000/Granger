# Granger

Your door to knowledge. Always open.

An offline library for Android, with a librarian that answers from your books and shows you the page. Granger keeps Wikipedia, reference works, books and audiobooks on your phone and waits, with no signal, for whenever you need it: on a plane, off the grid, or wherever the network doesn't reach or isn't allowed to. Nothing you read or ask leaves the device.

Licensed under GPL-3.0 (see `LICENSE`).

## Build

Requires JDK 21 and the Android SDK (`ANDROID_HOME` set).

    ./gradlew assembleDebug           # build
    ./gradlew test                    # JVM unit tests
    ./gradlew installDebug            # install on a connected phone
    ./gradlew connectedDebugAndroidTest   # on-device tests (Pixel 6 over USB)
    tools/dev/push-packs.sh            # put a small Wikipedia ZIM and the test ZIM on the phone

`tools/pack-builder` builds Granger's own packs (Granger Classics) from Project Gutenberg; see `tools/pack-builder/pack_builder.py --help`.

Design: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`
