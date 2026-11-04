"""python3 -m unittest tools.feedback.test_pull  (from the repo root), or python3 tools/feedback/test_pull.py"""
import pathlib, sys, tempfile, unittest
from email.message import EmailMessage

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import pull


def feedback_mail(subject="Granger feedback · 0.1.853 (853)", body="Search found nothing for bowline.\n\n--\nPixel 7 · Android 15", attach=True, html_only=False):
    m = EmailMessage()
    m["From"] = "A Tester <tester@example.org>"
    m["To"] = "feedback@opengranger.org"
    m["Subject"] = subject
    m["Date"] = "Tue, 04 Nov 2026 10:15:00 +0100"
    m["Message-ID"] = "<abc@example.org>"
    if html_only:
        m.set_content("<html><body><p>Hello</p><p>It <b>crashed</b> twice.<br>Then it was fine.</p></body></html>", subtype="html")
    else:
        m.set_content(body)
    if attach:
        m.add_attachment("java.lang.IllegalStateException at Reader\n", filename="granger-report.txt")
    return m.as_bytes()


class ParseTest(unittest.TestCase):
    def test_feedback_mail_is_parsed(self):
        info = pull.parse(feedback_mail())
        self.assertEqual(info["kind"], "feedback")
        self.assertEqual((info["build"], info["code"]), ("0.1.853", 853))
        self.assertEqual(info["from_addr"], "tester@example.org")
        self.assertEqual(info["date"].isoformat(), "2026-11-04T10:15:00+01:00")
        self.assertTrue(info["body"].startswith("Search found nothing for bowline."))
        self.assertEqual([n for n, _ in info["attachments"]], ["granger-report.txt"])

    def test_ask_report_and_other_subjects(self):
        self.assertEqual(pull.kind_of("Granger Ask report · 0.1.860 (860)"), "ask-report")
        self.assertEqual(pull.kind_of("Re: a question"), "other")
        self.assertEqual(pull.build_of("Re: a question"), (None, None))

    def test_html_only_body_is_flattened(self):
        info = pull.parse(feedback_mail(html_only=True, attach=False))
        self.assertEqual(info["body"], "Hello\n\nIt crashed twice.\nThen it was fine.")
        self.assertEqual(info["attachments"], [])

    def test_folder_name_and_rendering(self):
        info = pull.parse(feedback_mail())
        self.assertEqual(pull.folder_name("42", info), "2026-11-04_1015_42_feedback_a-tester")
        text = pull.render(info, "42")
        self.assertIn("# Granger feedback · 0.1.853 (853)", text)
        self.assertIn("- build: 0.1.853 (853)", text)
        self.assertIn("- attachments: granger-report.txt", text)
        self.assertIn("## Body\n\nSearch found nothing", text)

    def test_save_writes_mail_and_attachment(self):
        with tempfile.TemporaryDirectory() as d:
            pull.INBOX = pathlib.Path(d)
            folder = pull.save("7", feedback_mail())
            self.assertTrue((folder / "mail.md").exists())
            self.assertTrue((folder / "mail.eml").exists())
            self.assertEqual((folder / "granger-report.txt").read_text(), "java.lang.IllegalStateException at Reader\n")

    def test_config_reads_key_values(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "c.properties"
            p.write_text("# mailbox\nhost=imap.gmail.com\nuser=x@gmail.com\npassword=abcd efgh\n")
            cfg = pull.read_config(p)
            self.assertEqual(cfg, {"host": "imap.gmail.com", "user": "x@gmail.com", "password": "abcd efgh", "folder": "INBOX"})


if __name__ == "__main__":
    unittest.main()
