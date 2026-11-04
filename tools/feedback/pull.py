#!/usr/bin/env python3
"""Pulls the feedback mailbox (feedback@opengranger.org, forwarded to Granger's Gmail) over IMAP into tools/feedback/inbox/,
one folder per message with the mail as Markdown and its attachments (the app's granger-report.txt crash log) beside it,
so a session can grep the feedback offline. Messages are read with BODY.PEEK, so the mailbox's unread state is untouched.

Credentials: ~/.config/granger/feedback-mail.properties (never in the repo), lines of key=value:
    host=imap.gmail.com
    user=<the Gmail account the domain forwards to>
    password=<a Gmail app password: Google account → Security → 2-Step Verification → App passwords>
    folder=INBOX            (optional)

Run:  python3 tools/feedback/pull.py            new messages since the last run
      python3 tools/feedback/pull.py --all      every message again (folders are overwritten)
      python3 tools/feedback/pull.py --dry-run  list what would be saved

inbox/ is ignored by git: it holds testers' addresses and phones (the brand spec keeps testers unnamed)."""
import argparse, email, email.policy, html, imaplib, json, pathlib, re, sys
from email.utils import parsedate_to_datetime, parseaddr

HERE = pathlib.Path(__file__).resolve().parent
INBOX = HERE / "inbox"
STATE = INBOX / ".state.json"
CONFIG = pathlib.Path.home() / ".config/granger/feedback-mail.properties"
KINDS = [("Granger Ask report", "ask-report"), ("Granger feedback", "feedback")]
BUILD_RE = re.compile(r"·\s*(\S+)\s*\((\d+)\)")


def read_config(path=CONFIG):
    if not path.exists():
        sys.exit(f"no mailbox config at {path}; see the docstring")
    cfg = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            cfg[k.strip()] = v.strip()
    for k in ("host", "user", "password"):
        if k not in cfg:
            sys.exit(f"{path} needs {k}=")
    cfg.setdefault("folder", "INBOX")
    return cfg


def kind_of(subject):
    """The app's subjects: "Granger feedback · 0.1.N (code)" and "Granger Ask report · 0.1.N (code)"; anything else is "other"."""
    for prefix, kind in KINDS:
        if subject.startswith(prefix):
            return kind
    return "other"


def build_of(subject):
    m = BUILD_RE.search(subject)
    return (m.group(1), int(m.group(2))) if m else (None, None)


def html_to_text(s):
    s = re.sub(r"(?is)<(script|style).*?</\1>", "", s)
    s = re.sub(r"(?i)</p>|</div>", "\n\n", s)
    s = re.sub(r"(?i)<br\s*/?>|</li>|</tr>", "\n", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = html.unescape(s)
    return re.sub(r"\n{3,}", "\n\n", s).strip()


def body_of(msg):
    """The plain text part, or the HTML part flattened; attachments are not bodies."""
    plain = msg.get_body(preferencelist=("plain",))
    if plain is not None:
        return plain.get_content().strip()
    rich = msg.get_body(preferencelist=("html",))
    if rich is not None:
        return html_to_text(rich.get_content())
    return ""


def attachments_of(msg):
    out = []
    for part in msg.iter_attachments():
        name = part.get_filename() or f"part-{len(out) + 1}.bin"
        name = re.sub(r"[^\w.\-]+", "_", name)[:80] or "attachment"
        out.append((name, part.get_content() if part.get_content_maintype() == "text" else part.get_payload(decode=True)))
    return out


def slug(s, n=40):
    s = re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
    return s[:n].rstrip("-") or "mail"


def parse(raw):
    """Everything the folder needs from one message's bytes."""
    msg = email.message_from_bytes(raw, policy=email.policy.default)
    subject = (msg.get("Subject") or "").replace("\r", "").replace("\n", " ").strip()
    try:
        date = parsedate_to_datetime(msg.get("Date"))
    except (TypeError, ValueError):
        date = None
    name, addr = parseaddr(msg.get("From") or "")
    build, code = build_of(subject)
    return {
        "subject": subject, "kind": kind_of(subject), "build": build, "code": code,
        "date": date, "from_name": name, "from_addr": addr, "to": msg.get("To") or "",
        "message_id": (msg.get("Message-ID") or "").strip(), "body": body_of(msg), "attachments": attachments_of(msg),
    }


def folder_name(uid, info):
    stamp = info["date"].strftime("%Y-%m-%d_%H%M") if info["date"] else "undated"
    return f"{stamp}_{uid}_{info['kind']}_{slug(info['from_name'] or info['from_addr'].split('@')[0])}"


def render(info, uid):
    head = [f"# {info['subject'] or '(no subject)'}", "",
            f"- kind: {info['kind']}",
            f"- build: {info['build']} ({info['code']})" if info["build"] else "- build: unknown",
            f"- date: {info['date'].isoformat() if info['date'] else 'unknown'}",
            f"- from: {info['from_name']} <{info['from_addr']}>".replace("  <", " <"),
            f"- uid: {uid}", f"- message-id: {info['message_id']}"]
    if info["attachments"]:
        head.append("- attachments: " + ", ".join(n for n, _ in info["attachments"]))
    return "\n".join(head) + "\n\n## Body\n\n" + (info["body"] or "(empty)") + "\n"


def save(uid, raw, dry=False):
    info = parse(raw)
    folder = INBOX / folder_name(uid, info)
    if dry:
        print(f"would save {folder.name}: {info['subject']}")
        return folder
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "mail.md").write_text(render(info, uid))
    (folder / "mail.eml").write_bytes(raw)
    for name, data in info["attachments"]:
        p = folder / name
        p.write_text(data) if isinstance(data, str) else p.write_bytes(data or b"")
    print(f"saved {folder.name}: {info['subject']}")
    return folder


def load_state():
    try:
        return json.loads(STATE.read_text())
    except (OSError, ValueError):
        return {}


def pull(cfg, everything=False, dry=False):
    INBOX.mkdir(exist_ok=True)
    state = load_state()
    key = f"{cfg['user']}@{cfg['host']}/{cfg['folder']}"
    box = imaplib.IMAP4_SSL(cfg["host"])
    box.login(cfg["user"], cfg["password"])
    status, data = box.select(cfg["folder"], readonly=True)
    if status != "OK":
        sys.exit(f"cannot open {cfg['folder']}: {data}")
    validity = box.response("UIDVALIDITY")[1][0].decode() if box.response("UIDVALIDITY")[1] else ""
    last = state.get(key, {})
    start = 1 if everything or last.get("validity") != validity else int(last.get("uid", 0)) + 1
    status, data = box.uid("search", None, f"UID {start}:*")
    uids = [u for u in data[0].split() if int(u) >= start] if status == "OK" and data and data[0] else []
    saved = 0
    for uid in uids:
        status, parts = box.uid("fetch", uid, "(BODY.PEEK[])")
        if status != "OK" or not parts or not isinstance(parts[0], tuple):
            print(f"skip uid {uid.decode()}: {status}", file=sys.stderr)
            continue
        save(uid.decode(), parts[0][1], dry)
        saved += 1
        if not dry:
            state[key] = {"validity": validity, "uid": int(uid)}
            STATE.write_text(json.dumps(state, indent=1))
    box.logout()
    print(f"{saved} message(s) {'listed' if dry else 'saved'} to {INBOX}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--all", action="store_true", help="pull every message, not just the new ones")
    ap.add_argument("--dry-run", action="store_true", help="list what would be saved")
    ap.add_argument("--config", type=pathlib.Path, default=CONFIG)
    a = ap.parse_args()
    pull(read_config(a.config), everything=a.all, dry=a.dry_run)


if __name__ == "__main__":
    main()
