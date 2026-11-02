# Play policy check

Date: 2026-11-02. F1 part 2, Task 6 (plan `2026-10-31-plan-f1b-friend-build.md`, Decision 6), done on branch `cl-cleanup` during the clean-up pass. Policies were read on 2026-11-02; re-read any you're unsure of in Play Console before submitting, since Google edits them without notice.

Each section gives what Play asks, Granger's answer for the Console, and any change needed first.

## Summary: what has to change before the closed test

| # | Change | Why | State |
|---|---|---|---|
| 1 | **Report this answer** in Ask, without leaving the app | AI-Generated Content policy | To do: needs F1's Send feedback (on `f1-friend-build`), so it goes there or after that merges |
| 2 | archive.org's 1930 filter fixed in the Play build | Intellectual Property policy | **Done** (`cl-cleanup`, `Features.ARCHIVE_WAIVER`) |
| 3 | A privacy policy at a public URL | Required for every app | Draft: `docs/friend-testing/privacy-policy.md`; host it (GitHub Pages or the repo's raw page) |
| 4 | Medical answers in release: banner plus quoted passages, without generated prose | Health misinformation risk under Health Content and Services; on the release checklist | To do (Ask work) |
| 5 | Clásicos works first published after 1930 kept out of what the Play build offers | US copyright; Granger hosts them | To do: check publication years (see §3) |
| 6 | Foreground-service videos: one for media playback, one for downloads | FGS declaration | To record on the phone |

Nothing else blocks. The account rule below sets the timeline.

## 1. Production access for a new personal account

**Rule** ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465)): personal accounts created after 13 November 2023 need a **closed test with at least 12 testers opted in continuously for the 14 days** before they can apply for production. Internal testing doesn't count. Passing the 14 days doesn't grant access by itself: Google reviews the application, in about 7 days by vendor reports. Organisation accounts are exempt.

**Action:** create a closed testing track now, add the testers by email list or Google Group, and move round 1 there. Aim for 15–20, because invites aren't opt-ins and some testers drop out. Keep them opted in until production access is granted.

## 2. AI-Generated Content

**Policy** ([answer 13985936](https://support.google.com/googleplay/android-developer/answer/13985936)): it covers "text-to-text conversational generative AI chatbots, in which interacting with the chatbot is a central feature of the app." Such apps "must contain in-app user reporting or flagging features" that "allow users to report or flag offensive content to developers without needing to exit the app," and must prevent the generation of Restricted Content.

**Granger:** Ask is a chat with a model and one of the app's main tools, so it's in scope. Send feedback opens the mail app, which arguably counts as leaving the app. A dedicated in-app action is the safe reading.

**Change:** a **Report** action on every Ask reply (answers and best guesses). Tapping it opens a short sheet: what's wrong (offensive, wrong, unsafe, other), an optional note, and what's included (the question, the reply and its source titles). Send then goes through F1's mail route. The tap happens inside the app, and the sheet is the in-app flag. Keep the reports and use them to tune the prompts and the medical rules.

Restricted Content: Gemma 4 E2B is safety-tuned, and Ask answers from cited passages or declines. Best guesses are behind a tap and labelled. No extra filter is needed for the closed test; revisit if reports show a problem.

## 3. Intellectual Property

**Policy** ([answer 9888072](https://support.google.com/googleplay/android-developer/answer/9888072)): no apps that "encourage or induce infringement", including apps that let users download copyrighted works without authorisation. Google can ask for evidence of rights.

**Granger:**
- **archive.org:** the Play build shows only public-domain texts (published 1930 or earlier) and recordings with a usable date. The waiver ("Show everything") is gone from release builds, so there's no way past the filter. Spoken word stays empty in the Play build without the waiver; LibriVox covers it. **Done.**
- **Kiwix packs:** Granger lists them and downloads them from Kiwix's own mirrors, with each family's licence shown. Granger hosts none of them.
- **Granger Classics:** public domain in the US (published 1930 or earlier), built and hosted by Granger with a rights guard.
- **Clásicos Granger: a risk to check.** Its rule is authors dead by 1945, which makes the works public domain in Spain and Argentina. The US test is different: a work first published in 1931 or later can still be under US copyright even if its author died long ago (copyright restored under the URAA). Play is a US store, and Granger hosts these files itself. **Check each Clásicos work's first publication year; keep anything published after 1930 out of the catalog the Play build reads** (or out of the release altogether) until it's cleared.
- **Imports:** the user's own DRM-free files. DRM is detected and refused.
- **Store listing:** don't describe Granger as a way to get any book free. Say public-domain classics and open reference.

## 4. Non-commercial (NC) packs

Some Kiwix families are CC BY-NC. Granger is free with no ads or purchases, and never hosts or re-hosts NC packs, so NC terms hold for the closed test and launch. **Plan 10's Supporter purchase must revisit this:** keep NC packs out of anything paid, as the research-eval spec §10a already says, and check that a paid extra in the same app doesn't make the app's use of NC content "commercial" under the pack licences.

## 5. Device and Network Abuse: downloaded code

**Policy** ([answer 9888379](https://support.google.com/googleplay/android-developer/answer/9888379)): no downloading executable code (dex, JAR, .so) from outside Play. Code in an interpreter with indirect API access (JavaScript in a WebView) is exempt.

**Granger:** the downloads are data. The model files are `.litertlm` weights run by the LiteRT-LM library already in the app, voices are ONNX weights, and packs are ZIM, EPUB and PDF. All native code ships in the bundle. Pages in ZIM packs run their JavaScript in the reader's WebView, the listed exemption. The policy doesn't mention ML models; weights interpreted by bundled code fit the data reading. **No change.** If a reviewer asks: "Model and voice files are weights loaded by libraries bundled in the app; no code is downloaded."

## 6. Foreground services

**Policy** ([answer 13392821](https://support.google.com/googleplay/android-developer/answer/13392821)): apps targeting Android 14+ declare each foreground service type under App content, with a description, the effect if it's deferred or interrupted, a use case, and a video link showing how a user starts it.

| Type | Where | Use case | If deferred |
|---|---|---|---|
| `mediaPlayback` | `:core:player` PlaybackService | Music, audiobooks and read-aloud continue with the screen off or in another app | Playback stops mid-book or mid-song; read-aloud slows sharply off the big cores |
| `dataSync` | `:core:downloads` (WorkManager foreground) | User-started downloads of packs, books, models and voices, often several GB | The download pauses and resumes later; a large pack can take hours longer |

**Action:** record two short screen videos on the Pixel 7 (start a book's Listen and lock the screen; get a pack and leave the app), upload them unlisted, and paste the links.

## 7. Data safety

- **Data collected:** none. Granger has no account, analytics, ads or crash reporting service. The crash log stays on the phone.
- **Data shared:** none.
- **Send feedback** composes an email in the user's own mail app, which the user reviews and sends. Granger itself transmits nothing. If the Console wording leaves doubt, the conservative answer is: "App activity: other user-generated content and crash logs, collected optionally, not shared, used for app functionality and analytics."
- **Network requests** go to GitHub (the signed catalog), Hugging Face (models), Kiwix mirrors, archive.org, LibriVox and Project Gutenberg, all over HTTPS. They fetch content; no user data is sent beyond what any HTTP request carries.
- **Microphone (voice input, Plan A1, 2026-11-02):** `RECORD_AUDIO`, asked for on the first mic tap with a one-line reason (prominent disclosure). Speech goes to the system's **on-device** recognizer (`SpeechRecognizer.createOnDeviceSpeechRecognizer`; on Pixels, Android System Intelligence), and Granger receives only the text. Nothing is recorded, stored or transmitted, and the mic is hidden on phones without on-device recognition. So under Play's definition (data processed only on the device isn't "collected"), **Audio → Voice or sound recordings: not collected, not shared.** `RECORD_AUDIO` isn't a restricted permission, so no declaration form is needed.
- **Encrypted in transit:** yes. **Users can request deletion:** not applicable (nothing collected); everything is on the phone and goes with the app.

## 8. Health apps declaration

**Rule** ([answer 14738291](https://support.google.com/googleplay/android-developer/answer/14738291)): every app completes it, including apps on closed tracks.

**Answer:** **Medical Reference and Education** only (WikiMed, first-aid and medical packs, and Ask's medical answers with their banner). Granger is not a medical device, not a symptom checker, and reads no health data.

## 9. Content rating (IARC questionnaire)

Category: Reference, News or Educational. Answer honestly about what the library holds:
- Reference and literature that describe violence, sexuality, drug use and crude language in an educational or literary context (Wikipedia, the Classics, scripture).
- No user-to-user communication, no purchases, no location sharing.
- Users can download content from outside sources (Kiwix, archive.org's public-domain texts) and Ask generates text.

Expect a teen or 12+ rating in most regions. That's right for a general library.

## 10. Target audience and app access

- **Target audience:** 18 and over. That keeps Granger out of the Families policy, which a general library with an unfiltered encyclopedia and a chatbot wouldn't meet.
- **App access:** everything works without a login. Note for reviewers: Ask needs a phone with 6 GB of RAM or more and the model downloaded (Settings → Models, about 2 GB).
- **Ads:** none.

## 11. Models and licences

- Gemma 4 E2B: Apache-2.0 (catalog entry). EmbeddingGemma: Apache-2.0.
- Piper voices: Owl, Nanny and Professor are Granger's own trained voices (Maggie and Martin left the catalog on 2026-11-02), built from public-domain or CC BY sources; credit them in About.
- Credit list in About: libkiwix (GPL-3.0), Readium (BSD-3), sherpa-onnx (Apache-2.0), LiteRT-LM (Apache-2.0), TagLib (LGPL), Kiwix content licences per pack.

## Store listing notes

- Lead with the library and the fair deal. Present Ask as a Preview on phones with 6 GB or more.
- Don't name rival apps. Avoid "free books" phrasing that implies anything beyond public domain.
- Spanish listing alongside the English one.
