# Feedback mailbox

Testers' feedback and Ask reports go to **feedback@opengranger.org** (`FEEDBACK_EMAIL` in `app/build.gradle.kts`, also the
privacy policy's contact and the Play Console feedback channel). The domain's DNS is at Vercel and Vercel hosts no mail, so
ImprovMX forwards the address to a Gmail account made for Granger (decision 2026-11-04: free forwarding, not a paid
mailbox). Replies from that account go out from its gmail.com address.

## Two ways to read it

1. **The Gmail connector in claude.ai** (Settings → Connectors → Gmail), authorized with the Granger Gmail account, not
   Neal's own. Its tools then appear in Claude Code sessions, for live triage.
2. **`pull.py`**, the backstop that works with any IMAP mailbox: saves each message to `inbox/<date>_<uid>_<kind>_<sender>/`
   as `mail.md` (subject, kind, build, date, sender, body), `mail.eml` and its attachments (`granger-report.txt`, the crash
   log). `inbox/` is ignored by git. New messages only, by UID, unless `--all`.

        python3 tools/feedback/pull.py
        python3 tools/feedback/test_pull.py

   Credentials in `~/.config/granger/feedback-mail.properties` (beside the signing key, never in the repo): `host=imap.gmail.com`,
   `user=`, `password=` a Gmail **app password** (the account needs 2-Step Verification; Google account → Security →
   App passwords). IMAP must be on in that Gmail's settings (Forwarding and POP/IMAP) on older accounts; new ones have it on.

## Setting the forwarding up (Neal, once)

1. Make the Gmail account (for example `opengranger.feedback@gmail.com`); turn on 2-Step Verification; make an app password.
2. At improvmx.com, add the domain `opengranger.org` and an alias `feedback` → that Gmail. (A catch-all `*` → the same
   account also works, and then `privacy@` and `hello@` land too.)
3. In Vercel → Domains → opengranger.org → DNS Records, add what ImprovMX shows: two MX records (`mx1.improvmx.com`
   priority 10, `mx2.improvmx.com` priority 20, name `@`) and a TXT `v=spf1 include:spf.improvmx.com ~all` on `@`. ImprovMX
   checks them within minutes.
4. Send a test to feedback@opengranger.org; it should arrive in the Gmail. Then `python3 tools/feedback/pull.py --dry-run`.

Triage lives in the release checklist B (weekly): read `inbox/`, fix what blocks, log the rest in the roadmap. Keep
testers' names and addresses out of commits and docs (brand spec §6).
