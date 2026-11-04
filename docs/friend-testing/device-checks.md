# Device checks before the closed test

From the clean-up list (2026-11-02): three blockers that only a phone can clear. Write each result next to its line (pass, fail and what happened, the phone and build).

## 1. The player's hardware controls (Neal, on the Pixel 6)

Run each one for **music**, an **audiobook** and **read-aloud** (a Classics book with Listen):

- [ ] Notification: play/pause, next, previous, seek bar.
- [ ] Lock screen: the same controls, and the artwork and title are right.
- [ ] Bluetooth headphones: play/pause, next and previous (double and triple press, or the headphones' own buttons).
- [ ] Wired or USB-C headset button, if you have one.
- [ ] Unplugging or disconnecting headphones pauses (it must not carry on through the speaker).
- [ ] A phone call pauses and the end of the call resumes (passed in M1; recheck on read-aloud).
- [ ] Sleep timer, speed, chapters and the queue sheets each open and do what they say.
- [ ] ReplayGain and the equaliser (Settings → Listening): an audible change, no crash.

## 2. An SD card (a tester's phone with a microSD slot; Pixels have none)

Assigned 2026-11-04 to the E1 field tester (Sony Xperia 10 II, 4 GB, microSD); `economy-phone-note.md` asks him for it.

- [ ] Settings → Storage lists the card; pick it as the download location.
- [ ] Get a small pack (Knots or a Wikivoyage region) and a Classics book; both open.
- [ ] Restart the phone: both still open.
- [ ] With Granger closed, take the card out: the pack shows as missing, not as a crash. Put it back: it opens again.
- [ ] Music on the card: Listen finds it after a scan.

## 3. Read-aloud on a 4 GB phone (a tester's phone)

Assigned 2026-11-04 to the E1 field tester (Sony Xperia 10 II, 4 GB, microSD); `economy-phone-note.md` asks him for it.

The voice uses about 420 MB while speaking. Ask is hidden under 6 GB, but read-aloud isn't.

- [ ] Get the default Granger voice; open a Classics chapter; Listen; lock the screen for 10 minutes.
- [ ] Then the same with a Wikipedia article open in another tab and music paused in Listen.
- [ ] Note whether reading stops, stutters or the app restarts, and the phone model.

**If it fails:** on phones under 6 GB, default to the phone's own voice and offer the Granger voices with a note that they may stop on this phone. (Decision for Neal; nothing changes unless it fails.)
