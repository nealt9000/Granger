# Plan I8: the real iPhone, then friends

Spec: `docs/superpowers/specs/2026-11-20-iphone-design.md` (§6, I8 Friends). Parity list: `docs/superpowers/ios-parity.md`. Hand-off: `ios/HANDOFF.md`. Follows I7 (merged and pushed main fbc68437, release ios-core-2026-11-23).

**Goal.** The iPhone app goes from Neal's Simulator test to friends' and family's phones:
- Neal's Simulator findings are fixed;
- everything the Simulator couldn't show is checked on Neal's iPhone 16 Plus: the mic (built once the check passes), the Music library, remote buttons and calls, CarPlay, live LibriVox and archive.org, the widget and the Control Center control on a signed build, MetricKit, and the speed of search by meaning;
- a Release build that's ready for the App Store: debug hooks left out, a version number on both phones from the same commit, the export-compliance key, and an archive script;
- the licence side: AETHERBOUND LLC's GPLv3 §7 App Store permission for Granger's own code, and a letter to Kiwix about libkiwix and libzim, for Neal to send;
- app-developer friends build from the repo with a short note;
- TestFlight under AETHERBOUND LLC: Neal's own phone first, then the family.

When I8 merges, no parity row says "I8". Each row is ticked, or marked ≈ or — with its reason.

**Shape.** One branch, three parts, merged as each is green:
- **I8a: desk work.** Neal's findings, the Release build, the licence text and the letter. It starts now; Neal's findings join as they arrive.
- **I8b: on Neal's iPhone.** It starts when Neal plugs the phone in, unlocks it and turns on Developer Mode. It opens with the mic check, a one-hour spike; if the spike fails, the plan reports and goes on.
- **I8c: friends.** The friend note, then TestFlight. The family's group waits for the gate in call 1.

The App Store listing and review are not part of I8; they come after the family has used the app.

**Where things stand** (survey of main 882a4ee1):
- **Device.** Neal's test iPhone is a 16 Plus with 8 GB of memory (I0 Task 0), and he's enrolled in the Apple Developer Program. No signed build has been on it yet. `ios/Local.xcconfig` holds the team and a bundle prefix; the app group is `group.<prefix>`.
- **Mic.** `SpeechSpikeTests` (2) wait for a device. `VoiceInput`, `Dictation`, `SpokenTitles` and `SpokenTitleMatcher` are shared. The Swift `RecognizerBackend`, the Search mic and Voice input in Settings aren't built.
- **CarPlay.** The scene and `CarPlayTests` are built. The entitlement `com.apple.developer.carplay-audio` was requested by Neal under AETHERBOUND LLC; until Apple grants it, only Simulator builds carry the key (`Granger-Simulator.entitlements`). Nobody has looked at it in the CarPlay window yet. There's no CarPlay search or Siri.
- **Release.** `MARKETING_VERSION` "0.1", `CURRENT_PROJECT_VERSION` "1". 26 `-debug…` launch hooks; 13 source files have some `#if DEBUG`, but not every hook is behind one. There's no `ITSAppUsesNonExemptEncryption` key and no archive or upload script.
- **Licences.** `tools/ios/licences/licences.json` lists 36 entries. The GPL code nobody at Granger owns: libkiwix (GPL-3.0+) and libzim (GPL-2.0+), both Kiwix/openZIM's; Xapian (GPL-2.0+); eSpeak NG (GPL-3.0+, under Piper's phonemes). Granger's §7 permission is a placeholder on the licences screen.
- **Privacy.** `PrivacyInfo.xcprivacy` is in the app and the widget. The App Privacy answers are in I7's Task 4 note ("Data Not Collected"). The privacy page is live at opengranger.org.

## Builder's calls in this plan (await Neal)

1. **The order: dev friends, then Neal on TestFlight, then the family. The family waits on the licence gate.**
   - **App-developer friends build from the public repo.** No question arises: they build the GPL source themselves (spec §8). They can start as soon as I8a merges.
   - **TestFlight internal testing is Neal's own phone,** on App Store Connect's team, so there's no Beta App Review. It starts once the first archive uploads.
   - **The family's external group waits for two things.** First, the §7 permission is in the repo (call 2). Second, Kiwix has replied to the letter (call 3), or two weeks have passed with no reply and Neal decides.
2. **AETHERBOUND LLC grants a GPLv3 §7 additional permission for Granger's own code.**
   - It goes in `LICENSE-APPSTORE.md` beside `LICENSE`, in the README, and on the licences screen in place of the placeholder.
   - The draft wording, for Neal to change or have a lawyer read. It isn't legal advice: *"Additional permission under GNU GPL version 3 section 7: AETHERBOUND LLC, as copyright holder of Granger, gives you permission to convey this program, or a work based on it, through an app store whose terms add restrictions or usage rules (for example Apple's App Store and TestFlight), provided the Corresponding Source is also available under the GPL's terms. This permission covers only code whose copyright AETHERBOUND LLC holds; other components keep their own licences."*
   - First, the commit history is checked to confirm every author is Neal or the coding agent working for him, so the LLC can grant it for all of Granger's code.
3. **A letter to Kiwix, drafted for Neal to send from his own address.** I won't send it.
   - It asks Kiwix's view on Granger shipping libkiwix and libzim, their code, in an App Store and TestFlight app whose source is public under the GPL.
   - It asks how Kiwix's own iOS app handles Xapian, which they ship too.
   - **eSpeak NG has many copyright holders and nobody to ask.**
     - Recommendation: name it in the letter as the same question and accept the risk for the family's TestFlight.
     - The fallback, if Neal won't accept it: a build with the system voice only (no Piper voices) for the App Store, decided before the listing, not in I8.
4. **The mic is built only if the device spike passes.**
   - `SpeechSpikeTests` run on the 16 Plus. The test is SpeechAnalyzer (SpeechTranscriber, or DictationTranscriber when that's what the phone has) hearing the recorded phrase, with the shared `SpokenTitleMatcher` naming the book.
   - If it passes, the plan builds Android's pieces over the shared code: the Swift `RecognizerBackend`; the mic in Search; Voice input in Settings, shown only when the phone can listen, as on Android; and the spoken "Did you mean". English and Spanish, on device only, never a server (I6 call 8).
   - If it fails, the row becomes "≈ later" with what failed, and the plan goes on.
5. **I run the device checks over USB, and Neal does the physical parts.**
   - With the phone plugged in, unlocked by Neal and in Developer Mode, I run the tests with `xcodebuild test -destination 'platform=iOS,id=<udid>'` and read the logs and crash reports.
   - From a checklist, Neal presses the remote buttons, unplugs the headphones, takes a call, places the widget and the control, and plugs into a car or Apple's CarPlay Simulator app. I watch the logs while he does.
   - **The rules:**
     - I never get round the lock screen.
     - I touch only Granger's own data.
     - A test that clears data runs only on a Granger build.
   - Device runs use a `Device` test plan: the unit tests, `SpeechSpikeTests`, a new `MusicLibraryDeviceTests`, and a smoke set of the UI tests. The full UI suite stays on the Simulator, where its fixtures live.
6. **One version number on both phones, Android's scheme.**
   - `MARKETING_VERSION` is `0.1.<commits on main>` and `CURRENT_PROJECT_VERSION` is the commit count, the same as Android's `versionName` and `versionCode`.
   - It's set by `tools/ios/archive.sh` at archive time through an xcconfig. The committed project stays at the plain defaults.
   - The same commit gets the same number on both phones, so a report names one build.
7. **The Release build leaves the debug hooks out and is archived by a script.**
   - Every `-debug…` hook, the Report sheet's way in and the forced crash go behind `#if DEBUG`, as Android's debug extras need a debug build. A unit test fails if a Release build still answers one.
   - `ITSAppUsesNonExemptEncryption` is false: HTTPS only, which is exempt.
   - `tools/ios/archive.sh` builds the archive with the released GrangerCore, exports an `.ipa`, and uploads it with an App Store Connect API key that Neal makes.
     - The key is kept in `~/.config/granger/` and never in git.
     - Neal can upload from Xcode's Organizer instead.
8. **App Store Connect's answers are written down, and Neal enters them.**
   - **App Privacy:** "Data Not Collected", from I7's Task 4 note.
   - **Export compliance:** exempt.
   - **Age rating:** the questionnaire answered honestly. Granger opens Wikipedia and archive.org, adult flags are off by default, and medical and anatomy articles are possible.
     - Expect the highest rating. For TestFlight it hardly matters; it's looked at again before the App Store.
   - **Beta App Review notes** for the external group: what Granger is, that there's no account, and where to try search and a pack.
   - Each is a note in Task 9 for Neal to copy.
9. **CarPlay shows on a car only once Apple grants the entitlement. Search and Siri come with it.**
   - In I8 it's looked at in Xcode's CarPlay window and in Apple's CarPlay Simulator app with the phone.
   - If the entitlement arrives during I8:
     - the key moves into `Granger.entitlements` and the Simulator-only file goes;
     - "play … in Granger" is built with `INPlayMediaIntent` over the shared `MediaBrowse.search`, Android Auto's search.
   - If it doesn't arrive, the row stays ≈ with that reason.
10. **The friend note is `ios/HANDOFF.md`, rewritten for friends.**
    - One page:
      - building from a fresh clone with your own Apple team (a free Apple ID works except for the widget's app group, which is checked in Task 8);
      - what to try;
      - that it's a preview;
      - how to send findings.
    - The Neal-only parts move into this plan.
    - **TestFlight's "What to Test"** is a short text in English and Spanish.
11. **Findings come by Send feedback first; TestFlight's own feedback is read too.**
    - Send feedback stays the main way, by email to feedback@opengranger.org.
    - Testers can also send TestFlight screenshots, which go to App Store Connect. They're read there and logged with the rest. That's Apple's service, used only when a tester chooses it, and it doesn't change App Privacy.

## Global constraints

- **Android stays as it is.**
  - The JVM suite passes: `./gradlew test testAndroidHostTest jvmTest --continue`, 3940 at the start.
  - If a fix moves shared code, `:app:bundleRelease` builds and the change is checked on the Pixel 6. That check follows the shared-phone rules: `lastUpdateTime` and the DB version first, `adb -s` with the Pixel's serial, `app.granger/.MainActivity` started explicitly, Neal unlocks. Never the Pixel 7.
- **No migration.** The database version stays where it is.
- **Neal's iPhone is his own phone.**
  - Only Granger is installed or cleared.
  - I never get round the lock screen.
  - Nothing outside Granger is read.
  - Never open Mail's compose to send. A feedback test stops at the sheet.
- **Never send email or post** for Neal. The Kiwix letter and the family's invitations are his to send.
- **Secrets stay out of git:** the App Store Connect API key, the team in `Local.xcconfig`, and any profile.
- **Never name Claude** in anything user-facing: the friend note, What to Test, review notes, the letter. Write "coding agent" where it matters.
- **Builds and pushes:**
  - The repo is on the X10, which must be mounted.
  - One Gradle, Kotlin/Native or xcodebuild run at a time.
  - Pushing and GrangerCore releases have standing OK, after the noreply author check (`git log --format=%ae origin/main..HEAD`).
- **Branch and tests:**
  - Branch `i8-friends` off main 882a4ee1, in worktree `.claude/worktrees/i0`.
  - UI tests start from clean data, and the full Simulator suite runs from an uninstalled app. Fixtures come from the scratchpad.

## I8a: desk work

### Task 1: Neal's Simulator findings
- [ ] Each finding from Neal's test becomes a line here: what he saw, Android's behaviour, the fix, and the test that holds it.
- [ ] Items carried from I7 for him to look at, fixed if he says so:
  - Listen's controls shift at the largest text size as each sentence changes height.
  - Get more › Books by kind shows Granger Classics as Get-only. Check it against Android's.
  - A Wikipedia pack article's mark is worked out from the pack, not from Android's article copies.
  - The 115 iPhone-only Spanish phrases (I7 Task 3 note).
- [ ] Android's Search on the Pixel 6, now that it runs on shared code (I7 checked it only on an emulator), when the Pixel is connected.

### Task 2: The Release build
- [ ] Every `-debug…` hook behind `#if DEBUG` (call 7), and a unit test that a Release build ignores them.
- [ ] `ITSAppUsesNonExemptEncryption` false for the app and the widget.
- [ ] Versioning (call 6) through an xcconfig that `tools/ios/archive.sh` writes.
- [ ] `tools/ios/archive.sh`:
  - checks the released core is pinned (no `GRANGER_CORE_LOCAL`);
  - archives;
  - exports with an App Store export options plist;
  - `--upload` sends the build with the API key, or prints the Organizer steps when there's no key.
- [ ] A Release build runs in the Simulator: walk the main screens by hand. Check that a debug hook passed at launch does nothing, and that size and launch time are noted beside the Debug build's.

### Task 3: The licence text and the letter
- [ ] The commit authors check (call 2): every author is the noreply address or the coding agent's co-author line.
- [ ] `LICENSE-APPSTORE.md` with the §7 wording Neal approves. The README and the licences screen point to it, and `make_licences.py --check` still passes.
- [ ] The letter to Kiwix (call 3) in `docs/ios/kiwix-letter.md`, for Neal to send. The date it went and the reply are noted here.

### Task 4: I8a check and merge
- [ ] The Simulator suite from an uninstalled app, and the JVM suite.
- [ ] Merge, push, and a GrangerCore release if the core changed.

## I8b: on Neal's iPhone

### Task 5: The first signed build on the phone
- [ ] Neal sets his team in `ios/Local.xcconfig`. Automatic signing registers the App ID, the widget's ID and the app group.
- [ ] Install the Debug build. Run the `Device` test plan (call 5).
- [ ] The widget on the home screen and the Local only control in Control Center, placed by Neal. Play and pause, and Local only, flip from each.
- [ ] Check whether the `IntentsLiveEntityService` assert seen in the Simulator (I6 Task 8) happens on a signed build.
- [ ] Find what Neal's iPhone does differently from the Simulator: the first run and Pick a start over cellular and Wi-Fi, a pack download with the screen locked, a download that goes on after the app is swiped away.

### Task 6: The mic
- [ ] Spike (one hour): `SpeechSpikeTests` on the phone, in English and Spanish (call 4). Report if it fails.
- [ ] If it passes:
  - build `RecognizerBackend` over SpeechAnalyzer, the Search mic, Voice input in Settings, and the spoken "Did you mean", on the shared `VoiceInput`, `Dictation` and `SpokenTitles`;
  - write the iPhone-only words through `ios-only-strings.json` with Spanish;
  - add UI tests on the Simulator up to the mic button, and a device test that hears the recorded phrase and opens the book.

### Task 7: Audio on the phone
- [ ] The Music library's songs: which ones `AVAudioFile` opens from `ipod-library://` URLs. If they don't open, export them to a file first, and add a `MusicLibraryDeviceTests` case.
- [ ] From Neal's checklist: remote buttons (headphones, AirPods, the lock screen), headphones unplugged, and a phone call pausing and resuming the voice, a song and a book.
- [ ] CarPlay in Xcode's CarPlay window and in Apple's CarPlay Simulator app (call 9). If the entitlement is granted, the key, then search and Siri.
- [ ] LibriVox and archive.org audio against the live services: search, a download, play, and a bookmark.

### Task 8: Measurements and reports
- [ ] Search by meaning on the 16 Plus:
  - model load, first query and later queries;
  - the memory with the model loaded;
  - how long embedding an added long book takes.
  - Noted beside the Simulator's and the Pixel's. The phone's memory tier is read on real hardware. With 8 GB, the 6 GB cut-off can't be seen from this phone; that's noted.
- [ ] MetricKit:
  - Xcode's Debug › Simulate MetricKit Payloads on the phone lands in the crash log as Android's would;
  - a real payload is looked at a day later.
- [ ] A free Apple ID builds from a fresh clone. Check whether the app group fails without a paid team. If it does, the friend note says the widget needs a paid team, or the widget target is made optional.

### Task 9: I8b check and merge
- [ ] The `Device` test plan on the phone, and the Simulator and JVM suites.
- [ ] The parity rows that said I8 are ticked, ≈ or — with reasons.
- [ ] Merge and push.

## I8c: friends

### Task 10: The friend note and TestFlight
- [ ] `ios/HANDOFF.md` rewritten for friends (call 10); the What to Test text in English and Spanish.
- [ ] App Store Connect (Neal):
  - the app record under AETHERBOUND LLC;
  - the App Privacy, export, age rating and review notes from this task's note (call 8);
  - the privacy policy link.
- [ ] The first upload with `archive.sh --upload`. The upload's privacy report (ITMS-91053) is read and anything it lists is fixed.
- [ ] Internal testing on Neal's phone: install from TestFlight over nothing and walk the first run.
- [ ] When call 1's gate is met: the external group, Beta App Review, then Neal invites the family.
- [ ] Findings from friends and family logged here as they come. Each one is fixed here or starts a fix plan.

## Order and size

- **Starts now, without Neal:** Tasks 2 and 3.
- **Task 1** follows Neal's findings.
- **I8b** needs Neal with the phone for a sitting of about two hours (Tasks 5 to 8), and a second short one for the physical checklist and MetricKit's day-later look.
- **The biggest unknowns:**
  - whether SpeechAnalyzer works with the matcher (the mic spike);
  - whether the Music library's URLs open;
  - how long Kiwix and Apple take, which sets when the family's TestFlight starts.

**Not in I8:** the App Store listing, its screenshots and review; Ogg Vorbis (libvorbis waits on Neal); skip silence for audiobooks (later, I6 call 10).
