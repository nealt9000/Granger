# Play Console: the closed test, step by step

Date: 2026-11-04. For Neal, at the Console. The answers come from `play-policy-check.md` (read 2026-11-02; re-read a policy in the Console if unsure). The invitation that testers see is https://opengranger.org/test (Spanish: `/prueba`); its two links are set in the site's `build.py` once steps 1 and 3 below give them.

## Why the website route

Play's rule for a personal account made after 13 November 2023 ([answer 14151465](https://support.google.com/googleplay/android-developer/answer/14151465), re-read 2026-11-04): "a minimum of 12 testers who have been opted in continuously for at least 14 days" on a **closed** track, then an application that asks how the testers were recruited, whether they used every feature, and what changed from their feedback. Internal testing doesn't count. There is no public opt-in for a closed test: Play lets in only the accounts on the track's email lists or in its Google Groups ([answer 9845334](https://support.google.com/googleplay/android-developer/answer/9845334): "Only members of the specified Google Groups can join your test").

So the self-serve path is a **Google Group that anyone can join**, added to the closed track as its tester list. A stranger from the website joins the group, opens the opt-in link, taps Become a tester and installs. Nobody has to email Neal, and Neal never types an address into the Console. Until the group and the link exist, the page offers the email route and Neal adds those addresses to an email list by hand.

## 1. The Google Group

At groups.google.com, with the Play developer account:

- **Made 2026-11-04:** https://groups.google.com/g/granger-android-testers (`granger-android-testers@googlegroups.com`), added to the closed track as its tester list; the feedback channel is feedback@opengranger.org.
- Who can join: **Anyone on the web can join** (no approval, so the website works at any hour). Who can post: owners only (it's a list, not a forum; turn off email delivery for members, "No email").
- **Who can view members: group owners only**, and who can see the group's conversations: owners only. The brand spec's rule (`docs/superpowers/specs/2026-11-03-brand-mission-design.md` §6): testers stay unnamed, and no tester should see who else is testing.
- The group's URL is in `TEST_GROUP_URL` in `granger-site/build.py` (done); `TEST_OPTIN_URL` waits on step 3.

## 2. The closed track

Play Console → Testing → Closed testing → Create track (name it "Round 1"):

- Testers tab: add the Google Group by its address. Keep an email list beside it, "Round 1 by email", for the people who wrote in before the group existed.
- Countries: all, or at least the countries the testers are in.
- Feedback channel: `feedback@opengranger.org` (the address the app's Send feedback writes to; ImprovMX forwards it to Granger's Gmail, `tools/feedback/README.md`).
- Releases: upload the next release bundle with its mapping file (build from main once the pre-friends fixes and the Ask items are checked on the phone, release checklist A). Release notes in English and Spanish (`round-1-tester-note.md` and `round-1-tester-note-es.md`, under 500 characters each).
- Review and roll out. The first closed release goes through Google's review, which can take days; the opt-in link appears only once the release is published.

## 3. The opt-in link

After the release is published: Testing → Closed testing → Round 1 → Testers → "How testers join your test" → copy the link (`https://play.google.com/apps/testing/app.granger`). Paste it into `TEST_OPTIN_URL`, rebuild the site (`python3 build.py`), commit `index.html` and the four sub-pages, push. The test page switches from the email route to the three steps.

## 4. The forms, before the first closed release can be reviewed

Each lives under Policy → App content; the answers in full are in `play-policy-check.md`. **All eleven App content declarations were saved on 2026-11-04** (privacy policy, Sign in details, Ads, Advertising ID, Government apps, Target audience 18+, Data safety "no data collected", Foreground services, Content ratings; Financial features and Health were already actioned). Content rating came back ESRB Everyone 10+, PEGI Parental guidance, IARC 12+. The dashboard's two remaining setup steps are the app category + contact details and the store listing; until those are done, Publishing overview's "Send app for review" stays locked and the closed track's countries/testers steps show as locked too.

| Form | Answer |
|---|---|
| Privacy policy | `https://opengranger.org/privacy` (Spanish at `/privacidad`; the Console takes one URL) |
| Ads | No ads |
| App access | All functionality is available without special access. Note for reviewers: Ask needs a phone with 6 GB of RAM or more and the model downloaded under Settings → Models (about 2 GB) |
| Content rating (IARC) | Category Reference, News or Educational; literature and reference that describe violence, sexuality and drug use in an educational or literary context; no user-to-user communication, no purchases, no location sharing; users can download content from outside sources and Ask generates text. Expect a teen or 12+ rating |
| Target audience | 18 and over; not designed for children |
| News app | No |
| COVID-19 contact tracing | No |
| Data safety | Collects: none. Shares: none. Encrypted in transit: yes. Deletion: not applicable, nothing leaves the phone. If the form insists on something for Send feedback: "App activity: other user-generated content and crash logs, collected optionally, not shared, used for app functionality." Microphone: audio is processed on the device only, so not collected |
| Government apps | No |
| Financial features | None |
| Health | Medical reference and education only (WikiMed, first-aid packs, Ask's medical answers with their banner); not a medical device, no health data |
| Foreground services | `mediaPlayback` (music, audiobooks, read-aloud with the screen off) and `dataSync` (user-started downloads of packs, books, models and voices, often several GB). **Declared 2026-11-04** with two unlisted videos recorded on the Pixel 6 (build 1118; the shade's personal notifications blacked out in post): media playback https://youtube.com/shorts/C1YValy48ps (Listen on a book, Home, the playback notification); data sync https://youtube.com/shorts/fG4zEDn4cQ0 (Get a pack, Home, the download notification). Files: `~/Desktop/granger-play/granger-fgs-*.mp4` |
| AI-generated content | Yes, Ask generates text. In-app reporting: Report this answer on every reply and best guess |

Store listing (can be minimal for a closed test, but the fields are required): app name Granger, short description "Wikipedia, books and a librarian you can ask, on your phone, with no signal.", the full description's first paragraph from the brand spec §9, a 512 px icon, a feature graphic, at least two phone screenshots. Spanish listing alongside (release checklist B).

## 5. Keeping the 14 days

- The counter runs per tester from their opt-in; a tester who opts out and back in starts over. Aim for 15 to 20 opted in so 12 survive.
- Updates to the track don't reset anyone. Ship fixes freely.
- Check Testing → Closed testing → Round 1 → Testers for the opted-in count. When 12 have reached 14 days, the "Apply for production" button appears on the dashboard; the application asks about recruitment, feature coverage and what changed, which `release checklist B` (weekly triage) is written to answer.
