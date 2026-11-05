# Granger: a note for testers (round 1)

Thank you for trying Granger. It's a library that lives on your phone: encyclopedias, books, maps, guides and a reading voice, all downloaded once and kept, so they work with no signal. Nothing you do in it is sent anywhere.

This is an early test build. Some things are rough, and that's what your eyes are for.

## Getting it

1. Go to https://opengranger.org/test and follow the steps: join the testers group with the Google account on your phone, open the test page on Google Play and tap **Become a tester**, then install Granger from Play. Until the test opens, the page asks for your account's email instead and Neal sends you the link.
2. Play may mark the app as early access or unreviewed. That's expected for a test build.
3. Updates come through Play like any other app. Google counts testers who stay opted in for 14 days in a row, so don't leave the test or uninstall it during the test.

It needs Android 10 or newer. Ask, the librarian, needs a phone with 6 GB of memory or more. On smaller phones it's hidden and everything else works.

## Before you download much

The app is small. The library isn't, so check sizes and stay on Wi-Fi.

| What | Size |
| --- | --- |
| The app | about 50 MB |
| The suggested start (Starter) | about 9 GB, 11.5 with Ask |
| A full Wikipedia | about 100 GB |
| Ask's model | 2 GB |
| The reading voice | 67 MB |

- **Downloads wait for Wi-Fi.** "Download on Wi-Fi only" is on from the start (Settings → Library & storage). Leave it on unless you mean to spend mobile data.
- Granger asks before any download over 100 MB, and shows the size first.
- Settings shows how much Granger uses. Uninstalling Granger deletes everything it downloaded.

## What to try

1. **The first run.** Pick a start (or "Neither" and choose later), your region and what you'll use it for. Did the choices make sense?
2. **Get something and read it.** A Wikipedia pack, a guide (Wikivoyage, iFixit, first aid), or a book from Granger Classics. Then search for something you'd actually look up.
3. **Turn on airplane mode** and use it. That's the point of it.
4. **Listen.** Open a book or an article and tap Listen; the owl reads it aloud. Try it with the screen locked.
5. **Ask** (on 6 GB phones). Get the model under Settings → Models, then ask a question your library should answer. Tap a source to see where the answer came from.
6. **Home.** What does it show you, and is any of it useful?

## What to expect from Ask

Ask is a **preview**. It answers only from what's in your library and shows its sources, but it runs a small model on your phone, so:
- It takes a while: often 10–20 seconds before the first words.
- It can be wrong, or answer a nearby question instead of yours. Check the sources.
- For anything medical, treat it as a pointer to the article, not advice.
- When it can't find an answer it says so. That's on purpose.

Wrong or odd answers are some of the most useful feedback you can send. Include the question.

## Known issues

- Ask is slow and sometimes off target (see above).
- If the phone kills an e-book's page in the background, the book can close. Articles recover; books don't yet.
- Some packs are big and slow to download. A pause and resume is normal.
- The icon, names and wording are still changing.

## Sending feedback

**Settings → Send feedback.** Granger shows exactly what it will include (your phone model, Android version, free space, how many packs and books, no titles or searches) and any crash reports, which you can read first or leave out. Then it opens your mail app with an email to Neal. Write what happened, what you expected, or what you liked, and send it from there.

If Granger closes unexpectedly, the next time you open it Home offers to send a report. Nothing is sent unless you send it.

Screenshots help. So do "I didn't understand this" and "I gave up here".

## What's new (Play release notes, under 500 characters)

```
Round 1 test build. Thank you for trying Granger.
- Ask, the librarian, as a preview on phones with 6 GB or more. It answers from your library and shows sources; it can be wrong.
- Settings → Send feedback: an email to Neal with your phone and build, plus any crash reports. You see everything before it's sent.
- Downloads wait for Wi-Fi by default. The suggested start is about 9 GB.
The tester note has what to try and the known issues.
```

Shared as a doc: https://claude.ai/artifact/HnaLjrjCTxqH9caqSHo5fS (private until Neal shares it). The public version is https://opengranger.org/test; Spanish: `round-1-tester-note-es.md` and https://opengranger.org/prueba.
