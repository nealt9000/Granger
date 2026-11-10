# Granger

Your door to knowledge. Always open.

An offline library for Android. Granger keeps Wikipedia, 557 typeset classics, first aid, maps and repair guides on your phone, and waits, with no signal, for whenever you need it: on a plane, off the grid, or wherever the network doesn't reach or isn't allowed to.

- **One search across everything:** every pack, every page of every book, and your own ebooks, from one box.
- **Books set as books:** the Classics, in English and Spanish, typeset with covers; your EPUBs and PDFs beside them.
- **Read aloud:** offline voices made for Granger read any page, with the screen locked.
- **Your own collection:** ebooks, music and audiobooks in the same app.

The packs are [Kiwix](https://kiwix.org)'s open ZIM files, so anything Granger downloads can also be read by Kiwix's apps. No account, and nothing you read or search leaves the device.

Site: [opengranger.org](https://opengranger.org) · Licensed under GPL-3.0 (see `LICENSE`).

## Build

Requires JDK 21 and the Android SDK (`ANDROID_HOME` set).

    ./gradlew assembleDebug           # build
    ./gradlew test                    # JVM unit tests
    ./gradlew installDebug            # install on a connected phone
    ./gradlew connectedDebugAndroidTest   # on-device tests (Pixel 6 over USB)
    tools/dev/push-packs.sh            # put a small Wikipedia ZIM and the test ZIM on the phone

`tools/pack-builder` builds Granger's own packs (Granger Classics) from Project Gutenberg; see `tools/pack-builder/pack_builder.py --help`.

Design: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`
