"""Direction D: A's layout, one ground per section. Usage: sections.py REPO out.png"""
import sys
sys.argv = [sys.argv[0], sys.argv[1], "/dev/null"]
exec(open(__file__.replace("sections.py", "draft.py")).read().split("SCALE, PAD, LAB")[0])

GROUNDS = {"Scripture & religion": "5B3322", "Philosophy": "354059", "Epic & poetry": ENAMEL,
           "Drama": "6B2A2A", "Opera libretti": "4A3A5A", "Novels & stories": GUNMETAL}
SAMPLES = [("The King James Bible", "", None, "Scripture & religion"),
           ("Meditations", "Marcus Aurelius", "George Long", "Philosophy"),
           ("Paradise Lost", "John Milton", None, "Epic & poetry"),
           ("Hamlet", "William Shakespeare", None, "Drama"),
           ("La Bohème", "Giuseppe Giacosa and Luigi Illica", "William Grist and Percy Pinkerton", "Opera libretti"),
           ("Pride and Prejudice", "Jane Austen", None, "Novels & stories")]
base = STYLES["A  Gunmetal (the app's own cover tile)"]
cw, ch, PAD = 300, 450, 24
board = Image.new("RGB", (PAD + 3 * (cw + PAD), PAD + 2 * (ch + PAD)), hx("3A3F40"))
for i, b in enumerate(SAMPLES):
    s = dict(base, ground=GROUNDS[b[3]], pupils=GROUNDS[b[3]])
    board.paste(cover(b, s).resize((cw, ch), Image.LANCZOS), (PAD + (i % 3) * (cw + PAD), PAD + (i // 3) * (ch + PAD)))
board.save("/private/tmp/claude-501/-Users-s-nealthompson-Developer-Granger/092743aa-2ddb-40b7-961b-76f9d71054e6/scratchpad/covers/sections.png")
