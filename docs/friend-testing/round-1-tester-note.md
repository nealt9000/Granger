# Granger: a note for testers (round 1)

Thank you for trying Granger. It's a library that lives on your phone: encyclopedias, books, maps, guides and a reading voice, all downloaded once and kept, so they work with no signal. Nothing you do in it is sent anywhere.

This is an early test build. Some things are rough, and that's what your eyes are for.

## Getting it

1. Go to https://opengranger.org/test and follow the steps: join the testers group with the Google account on your phone, open the test page on Google Play and tap **Become a tester**, then install Granger from Play. Until the test opens, the page asks for your account's email instead and Neal sends you the link.
2. Play may mark the app as early access or unreviewed. That's expected for a test build.
3. Updates come through Play like any other app. Google counts testers who stay opted in for 14 days in a row, so don't leave the test or uninstall it during the test.

It needs Android 10 or newer.

**Ask, the librarian, is resting for now.** The models that fit on a phone today get too many answers wrong, and an answer you can't trust is worse than none. It will come back when on-device models can answer well. Meanwhile Search is getting the attention: tell us what you searched for and didn't find.

## Before you download much

The app is small. The library isn't, so check sizes and stay on Wi-Fi.

| What | Size |
| --- | --- |
| The app | about 50 MB |
| The suggested start (Starter) | about 9 GB |
| A full Wikipedia | about 100 GB |
| The reading voice | 67 MB |

- **Downloads wait for Wi-Fi.** "Download on Wi-Fi only" is on from the start (Settings → Library & storage). Leave it on unless you mean to spend mobile data.
- Granger asks before any download over 100 MB, and shows the size first.
- Settings shows how much Granger uses. Uninstalling Granger deletes everything it downloaded.
- If you downloaded Ask's model in an earlier build, you can remove it under Settings → Models to free about 2.5 GB.

## What to try

1. **The first run.** Pick a start (or "Neither" and choose later), your region and what you'll use it for. Did the choices make sense?
2. **Get something and read it.** A Wikipedia pack, a guide (Wikivoyage, iFixit, first aid), or a book from Granger Classics.
3. **Turn on airplane mode** and use it. That's the point of it.
4. **Listen.** Open a book or an article and tap Listen; the owl reads it aloud. Try it with the screen locked.
5. **Search** for things you'd actually look up: a name, a place, a half-remembered title, a question. When it doesn't find what you meant, send us the words you typed.
6. **Home.** What does it show you, and is any of it useful?

## Known issues

- If the phone kills an e-book's page in the background, the book can close. Articles recover; books don't yet.
- Some packs are big and slow to download. A pause and resume is normal.
- The icon, names and wording are still changing.

## Sending feedback

**Settings → Send feedback.** Granger shows exactly what it will include (your phone model, Android version, free space, how many packs and books, no titles or searches) and any crash reports, which you can read first or leave out. Then it opens your mail app with an email to Neal. Write what happened, what you expected, or what you liked, and send it from there.

If Granger closes unexpectedly, the next time you open it Home offers to send a report. Nothing is sent unless you send it.

Screenshots help. So do "I didn't understand this" and "I gave up here".

## What's new (Play release notes, under 500 characters)

The next build (Ask off):

```
Ask is resting while on-device AI catches up; it will come back when it can answer well. If you downloaded its model, Settings → Models can remove it to free about 2.5 GB. Everything else works as before, and Search is next: tell us what you searched for and didn't find.
```

Round 1, the first build:

```
Round 1 test build. Thank you for trying Granger.
- Ask, the librarian, as a preview on phones with 6 GB or more. It answers from your library and shows sources; it can be wrong.
- Settings → Send feedback: an email to Neal with your phone and build, plus any crash reports. You see everything before it's sent.
- Downloads wait for Wi-Fi by default. The suggested start is about 9 GB.
The tester note has what to try and the known issues.
```

Shared as a doc: https://claude.ai/artifact/HnaLjrjCTxqH9caqSHo5fS (private until Neal shares it). The public version is https://opengranger.org/test; Spanish: `round-1-tester-note-es.md` and https://opengranger.org/prueba.
