# Plan I8: a friends' build, real iPhones, then friends

Spec: `docs/superpowers/specs/2026-11-20-iphone-design.md` (§6, I8 Friends). Parity list: `docs/superpowers/ios-parity.md`. Hand-off: `ios/HANDOFF.md`. Follows I7 (merged and pushed main fbc68437, release ios-core-2026-11-23).

**Goal.** The iPhone app goes from Neal's Simulator test to friends' and family's phones:
- Neal's Simulator findings are fixed;
- **a friends' build** (a debug build with the testers' extras, call 7) that anyone can install with TestFlight, with no Mac, cable or device registration;
- everything the Simulator couldn't show is checked on **a remote friend's iPhone**:
  - the mic (built once the check passes);
  - the Music library;
  - remote buttons and calls;
  - CarPlay;
  - live LibriVox and archive.org;
  - the widget and the Control Center control;
  - MetricKit;
  - the speed of search by meaning;
- an in-app **Device check** that measures what it can on its own and sends the results by Send feedback, so the friend doesn't need a Mac;
- a version number shared by both phones, the export-compliance key, an archive and upload script, and AETHERBOUND LLC's GPLv3 §7 App Store permission for Granger's own code;
- app-developer friends build from the repo with a short note. Less technical friends and the family install the friends' build from a TestFlight link with a step-by-step guide.

When I8 merges, no parity row says "I8". Each row is ticked, or marked ≈ or — with its reason.

**Shape.** One branch, three parts, merged as each is green.
- **I8a: desk work.** Neal's findings, the builds and the script, the licence text, the Device check and the testers' checklist. Everything in I8a is checked in the Simulator. It starts now; Neal's findings join as they arrive.
- **I8b: TestFlight and the remote friend.**
  - App Store Connect, the first upload, and the remote friend as an internal tester.
  - Then rounds: the friend installs a build, runs the Device check and the checklist, and sends the reports; fixes follow and the next build goes up.
  - The mic is built only when the first round's speech check passes.
- **I8c: friends.** The friend note for developers. Then the external TestFlight group with a public link, the install guide, and Beta App Review. Then Neal shares the link.

The App Store listing and review are not part of I8; they come after the family has used the app.

**Where things stand** (survey of main 882a4ee1):
- **Device.** There's no iPhone on site (Neal, 2026-11-23): every device check is done by a friend remotely, so I can't run anything on the phone myself. Neal is enrolled in the Apple Developer Program under AETHERBOUND LLC. No signed build has been made yet.
- **Mic.** `SpeechSpikeTests` (2) wait for a device. `VoiceInput`, `Dictation`, `SpokenTitles` and `SpokenTitleMatcher` are shared. The Swift `RecognizerBackend`, the Search mic and Voice input in Settings aren't built.
- **CarPlay.** The scene and `CarPlayTests` are built. The entitlement `com.apple.developer.carplay-audio` was requested by Neal; until Apple grants it, only Simulator builds carry the key (`Granger-Simulator.entitlements`), and a TestFlight build won't show in a car. Nobody has looked at it in the CarPlay window yet. There's no CarPlay search or Siri.
- **Builds.**
  - `MARKETING_VERSION` "0.1", `CURRENT_PROJECT_VERSION` "1".
  - 26 `-debug…` launch hooks, plus `-resetData` (`DebugReset.swift`, debug builds only). 13 source files have some `#if DEBUG`, but not every hook is behind one.
  - No `ITSAppUsesNonExemptEncryption` key, and no archive or upload script.
- **Licences.** `tools/ios/licences/licences.json` lists 36 entries; Granger's §7 permission is a placeholder on the licences screen. The GPL code that isn't Granger's: libkiwix, libzim, Xapian and eSpeak NG. **Neal accepts that risk for now and the letter to Kiwix is skipped** (2026-11-23).
- **Privacy.** `PrivacyInfo.xcprivacy` is in the app and the widget. The App Privacy answers are in I7's Task 4 note ("Data Not Collected"). The privacy page is live at opengranger.org.

## Builder's calls in this plan (all OK, Neal 2026-11-23, with his changes)

Neal's changes on 2026-11-23:
- The letter to Kiwix is skipped and the licence risk is accepted for now.
- There's no test iPhone on site; a friend does all iPhone testing remotely.
- A debug build is wanted for less technical friends to install. The calls below are rewritten for these.

1. **The order: the remote friend, developer friends, then less technical friends and the family.**
   - **The remote friend** is an internal TestFlight tester: added to App Store Connect's team with the most limited role. Every build reaches them minutes after upload, with no Beta App Review.
   - **App-developer friends** build from the public repo as soon as I8a merges.
   - **Less technical friends and the family** use the external group's public link. That starts once:
     - the §7 permission is in the repo;
     - the first round on the remote friend's phone has no blocker;
     - Apple's Beta App Review has passed the build.
   - No letter to Kiwix: Neal accepts the risk for libkiwix, libzim, Xapian and eSpeak NG at this stage. It's looked at again before the App Store.
2. **AETHERBOUND LLC grants a GPLv3 §7 additional permission for Granger's own code.**
   - It goes in `LICENSE-APPSTORE.md` beside `LICENSE`, in the README, and on the licences screen in place of the placeholder.
   - The draft wording, for Neal to change or have a lawyer read. It isn't legal advice: *"Additional permission under GNU GPL version 3 section 7: AETHERBOUND LLC, as copyright holder of Granger, gives you permission to convey this program, or a work based on it, through an app store whose terms add restrictions or usage rules (for example Apple's App Store and TestFlight), provided the Corresponding Source is also available under the GPL's terms. This permission covers only code whose copyright AETHERBOUND LLC holds; other components keep their own licences."*
   - First, the commit history is checked to confirm every author is Neal or the coding agent working for him.
3. **The friends' build goes out by TestFlight, not ad hoc.**
   - TestFlight needs only the TestFlight app and a link. Ad hoc needs each phone's identifier, which is hard for a less technical friend to find, a new build for every new phone, and a page to install from.
   - TestFlight builds last 90 days, and each new upload replaces the old one on the testers' phones.
4. **The mic is built only if the remote friend's speech check passes.**
   - The Device check (call 5) runs SpeechAnalyzer (SpeechTranscriber, or DictationTranscriber when that's what the phone has) on device, with the shared `SpokenTitleMatcher`, in two ways:
     - on a phrase the app speaks into a file with the system voice;
     - on the friend saying "Pride and Prejudice" (and "Orgullo y prejuicio" in Spanish).
   - If it passes, the plan builds Android's pieces over the shared code: the Swift `RecognizerBackend`; the mic in Search; Voice input in Settings, shown only when the phone can listen, as on Android; and the spoken "Did you mean". The friend checks them in the next round.
   - On device only, never a server (I6 call 8). If it fails, the row becomes "≈ later" with what failed.
5. **Remote device checks: an in-app Device check plus a short checklist.**
   - **The Device check** is in the friends' build only, under Settings › About › Device check. It runs what can be measured without a person and builds a report:
     - the speech check (call 4);
     - search by meaning (model load, first and later query, memory with the model loaded);
     - the memory tier as read on the phone;
     - whether the first few Music library songs open (`ipod-library://` with `AVAudioFile`);
     - the app group round trip with the widget's snapshot;
     - MetricKit payloads received so far;
     - iOS version, model and free space.
     - Send report puts it into Send feedback's email beside the usual facts and the crash log.
   - **The checklist** (`ios/DEVICE-CHECKS.md`, English and Spanish) covers what needs hands, numbered so findings can name a step. A step that needs something the friend doesn't have (a car, headphones with buttons) is marked "skipped" and stays open:
     - remote buttons on headphones, AirPods and the lock screen;
     - headphones unplugged;
     - a call during playback;
     - placing the widget and the Control Center control;
     - a pack download with the screen locked, and after swiping the app away;
     - LibriVox and archive.org audio, live;
     - CarPlay if they have a car and the entitlement is in.
   - **If the friend has a Mac with Xcode,** they can also run the `Device` test plan on their phone with their own team. That's welcome, but nothing depends on it.
6. **One version number on both phones, Android's scheme.**
   - `MARKETING_VERSION` is `0.1.<commits on main>` and `CURRENT_PROJECT_VERSION` is the commit count, the same as Android's `versionName` and `versionCode`.
   - It's set by `tools/ios/archive.sh` at archive time. The committed project keeps the plain defaults.
7. **The friends' build is the debug build Neal asked for, made for phones rather than tests.**
   - It's a third build configuration, **Friends**:
     - the compiler's optimisation as in Release, so reading, search and playback feel as they will;
     - the testers' extras that Android's debug build has: the Device check, the Report sheet, a fuller log in Send feedback, and a "Friends build 0.1.N" line in About;
     - the UI tests' launch hooks (`-debug…`, `-resetData`) left out. Nobody can pass launch arguments to a TestFlight app anyway, but leaving the hooks out means none of them can wipe data or fake a source.
   - **Release**, for the App Store later, has none of the extras.
   - **Debug** stays the Simulator's and the UI tests'.
   - A unit test checks which extras each configuration has.
   - `ITSAppUsesNonExemptEncryption` is false (HTTPS only, which is exempt).
   - `tools/ios/archive.sh --config Friends|Release`:
     - checks the released core is pinned;
     - archives and exports for App Store Connect;
     - with `--upload`, sends the build with an App Store Connect API key that Neal makes, kept in `~/.config/granger/` and never in git, or prints Xcode Organizer's steps.
8. **App Store Connect's answers are written down, and Neal enters them.**
   - **App Privacy:** "Data Not Collected", from I7's Task 4 note.
   - **Export compliance:** exempt.
   - **Age rating:** answered honestly. Granger opens Wikipedia and archive.org, adult flags are off by default, and medical and anatomy articles are possible. Expect the highest rating; it's looked at again before the App Store.
   - **Beta App Review notes:** what Granger is, that there's no account, and where to try search and a pack.
   - **What to Test,** in English and Spanish.
9. **CarPlay shows on a car only once Apple grants the entitlement. Search and Siri come with it.**
   - In I8, Neal or I look at it in Xcode's CarPlay window on the Mac. A friend with a CarPlay car tries it once the entitlement is in.
   - If the entitlement arrives during I8, the key moves into `Granger.entitlements`, and "play … in Granger" is built with `INPlayMediaIntent` over the shared `MediaBrowse.search`.
   - If it doesn't, the row stays ≈ with that reason.
10. **Two notes for two kinds of friend.**
    - `ios/HANDOFF.md` is rewritten for **developer friends**: building from a fresh clone with their own Apple team, what to try, and how to send findings. Whether a free Apple ID works, including the widget's app group, is asked of the first developer friend and written in.
    - `ios/TESTFLIGHT.md` is a step-by-step **install guide** for less technical friends, in English and Spanish, short enough to paste into a message: install TestFlight, open the link, Install, then what to try and how to send feedback. Neal sends it with the link.
11. **Findings come by Send feedback first; TestFlight's own feedback is read too.**
    - Send feedback, carrying the Device check report, stays the main way, by email to feedback@opengranger.org.
    - Testers can also send TestFlight screenshots, which go to App Store Connect. They're read there and logged with the rest. That's Apple's service, used only when a tester chooses it, and it doesn't change App Privacy.

## Global constraints

- **Android stays as it is.**
  - The JVM suite passes: `./gradlew test testAndroidHostTest jvmTest --continue`, 3940 at the start.
  - If a fix moves shared code, `:app:bundleRelease` builds and the change is checked on the Pixel 6 under the shared-phone rules: `lastUpdateTime` and the DB version first, `adb -s` with the Pixel's serial, `app.granger/.MainActivity` started explicitly, Neal unlocks. Never the Pixel 7.
- **No migration.** The database version stays where it is.
- **Friends' phones are their own.**
  - The friends' build never reads anything outside Granger.
  - The Device check reads only its own measurements and the first few songs' playability. It never lists the song titles in its report.
  - Nothing is sent unless the friend taps Send.
- **Never send email or post** for Neal. TestFlight invitations, the public link and the install guide are his to send.
- **Secrets stay out of git:** the App Store Connect API key, the team in `Local.xcconfig`, and any profile.
- **Never name Claude** in anything user-facing: the notes, the guide, What to Test, review notes. Write "coding agent" where it matters.
- **Builds and pushes:**
  - The repo is on the X10, which must be mounted.
  - One Gradle, Kotlin/Native or xcodebuild run at a time.
  - Pushing and GrangerCore releases have standing OK, after the noreply author check (`git log --format=%ae origin/main..HEAD`).
- **Branch and tests:**
  - Branch `i8-friends` off main fc5ce05d, in worktree `.claude/worktrees/i0`.
  - UI tests start from clean data, and the full Simulator suite runs from an uninstalled app. Fixtures come from the scratchpad.

## I8a: desk work

### Task 1: Neal's Simulator findings
- [ ] Each finding from Neal's test becomes a line here: what he saw, Android's behaviour, the fix, and the test that holds it.
- [ ] Items carried from I7, fixed if Neal says so:
  - Listen's controls shift at the largest text size as each sentence changes height.
  - Get more › Books by kind shows Granger Classics as Get-only. Check it against Android's.
  - A Wikipedia pack article's mark is worked out from the pack.
  - The 115 iPhone-only Spanish phrases (I7 Task 3 note).
- [ ] Android's Search on the Pixel 6, now that it runs on shared code, when the Pixel is connected.

### Task 2: The builds and the script
- [ ] The Friends configuration and its extras flag; every UI-test hook behind `#if DEBUG` (call 7); a unit test of each configuration's extras.
- [ ] `ITSAppUsesNonExemptEncryption` false for the app and the widget.
- [ ] Versioning (call 6); `tools/ios/archive.sh --config … [--upload]`.
- [ ] A Friends build and a Release build each run in the Simulator: walk the main screens by hand, and check that a launch hook does nothing. Note the size and launch time beside the Debug build's.

### Task 3: The licence text
- [ ] The commit authors check (call 2).
- [ ] `LICENSE-APPSTORE.md` with the §7 wording Neal approves. The README and the licences screen point to it, and `make_licences.py --check` still passes.

### Task 4: The Device check and the checklist
- [ ] Settings › About › Device check in the Friends build (call 5): each measurement, Send report into Send feedback, and words in English and Spanish through `ios-only-strings.json`.
- [ ] The speech check runs on a spoken file and on a live phrase (call 4). In the Simulator it says "no recognizer on this phone", which is the result the Simulator gives today.
- [ ] `ios/DEVICE-CHECKS.md`, English and Spanish, with numbered steps.
- [ ] UI tests: the Device check runs in the Simulator, its report reaches the feedback text, and it doesn't exist in a Release build.

### Task 5: I8a check and merge
- [ ] The Simulator suite from an uninstalled app, and the JVM suite.
- [ ] Merge, push, and a GrangerCore release if the core changed.

## I8b: TestFlight and the remote friend

### Task 6: App Store Connect and the first upload
- [ ] Neal:
  - the app record under AETHERBOUND LLC;
  - the API key;
  - the answers from call 8;
  - the remote friend added as an internal tester.
- [ ] `archive.sh --config Friends --upload`. The upload's privacy report (ITMS-91053) is read and anything it lists is fixed.
- [ ] The friend installs from TestFlight and walks the first run with a Wikipedia download.

### Task 7: Round 1 on the friend's phone
- [ ] The Device check report and the checklist come back. Each result is logged here, and each failure is fixed or becomes an open row with its reason:
  - the speech check;
  - meaning-search speed and memory;
  - Music library playability;
  - the app group;
  - remote buttons and calls;
  - the widget and the control;
  - downloads with the screen locked;
  - live LibriVox and archive.org.
- [ ] If Music library songs don't open, export them to a file before playing.
- [ ] MetricKit: the friend sends the Device check again a day or more later, to see whether a real payload arrived and reached the crash log.
- [ ] CarPlay in Xcode's CarPlay window on the Mac (call 9).

### Task 8: The mic, and round 2
- [ ] If round 1's speech check passed, build the mic (call 4) with UI tests on the Simulator up to the mic button.
- [ ] The next Friends build goes up with round 1's fixes. The friend checks the mic in English and Spanish, and anything round 1 left.

### Task 9: I8b check and merge
- [ ] The Simulator and JVM suites.
- [ ] The parity rows that said I8 are ticked, ≈ or — with reasons, naming the round and the phone (model, iOS version) that showed each.
- [ ] Merge and push.

## I8c: friends

### Task 10: The notes and the external group
- [ ] `ios/HANDOFF.md` for developer friends, and `ios/TESTFLIGHT.md` with the install guide (call 10).
- [ ] The external group with a public link. The build goes to Beta App Review with call 8's notes.
- [ ] When call 1's conditions are met, Neal shares the link and the guide.
- [ ] Findings from friends and family are logged here as they come. Each one is fixed here, with a new Friends build, or starts a fix plan.

## Order and size

- **Starts now, without Neal:** Tasks 2, 3 and 4.
- **Task 1** follows Neal's findings.
- **Task 6** needs about an hour of Neal's time in App Store Connect.
- **Each remote round** takes about a day: upload, the friend's time, the report.
- **The biggest unknowns:**
  - whether SpeechAnalyzer works with the matcher;
  - whether the Music library's URLs open;
  - how quickly the remote friend can do a round;
  - how long Beta App Review takes.

**Not in I8:** the App Store listing, its screenshots and review; the licence question for the App Store (call 1); Ogg Vorbis (libvorbis waits on Neal); skip silence for audiobooks (later, I6 call 10).
