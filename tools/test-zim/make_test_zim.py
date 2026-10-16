"""Builds testdata/granger-test.zim, the tiny ZIM used by Granger's instrumented tests.

    python3 -m venv /tmp/zimenv && /tmp/zimenv/bin/pip install -r tools/test-zim/requirements.txt
    /tmp/zimenv/bin/python tools/test-zim/make_test_zim.py testdata/granger-test.zim
"""
import base64
import sys
from pathlib import Path

from libzim.writer import Creator, Hint, Item, StringProvider

# 1x1 transparent PNG.
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)
CSS = "body { font-family: serif; } h1 { color: #333; }"


def page(title: str, body: str) -> str:
    return (
        '<!DOCTYPE html><html><head><meta charset="utf-8">'
        f'<title>{title}</title><link rel="stylesheet" href="style.css"></head>'
        f"<body><h1>{title}</h1>{body}</body></html>"
    )


class Entry(Item):
    def __init__(self, path, title, mimetype, content, front):
        super().__init__()
        self.path, self.title, self.mimetype = path, title, mimetype
        self.content, self.front = content, front

    def get_path(self):
        return self.path

    def get_title(self):
        return self.title

    def get_mimetype(self):
        return self.mimetype

    def get_contentprovider(self):
        return StringProvider(self.content)

    def get_hints(self):
        return {Hint.FRONT_ARTICLE: self.front}


ARTICLES = [
    ("index", "Granger Test Library", page(
        "Granger Test Library",
        '<p>Start here.</p><ul>'
        '<li><a href="Photosynthesis">Photosynthesis</a></li>'
        '<li><a href="C%2B%2B">C++</a></li>'
        '<li><a href="What_is_this%3F">What is this?</a></li>'
        '<li><a href="Caf%C3%A9_au_lait">Café au lait</a></li>'
        '<li><a href="Plants">Plants (redirect)</a></li>'
        '<li><a href="https://example.com/">External link</a></li>'
        '</ul>')),
    ("Photosynthesis", "Photosynthesis", page(
        "Photosynthesis",
        '<p>Photosynthesis is the process by which green plants use sunlight, '
        'water and carbon dioxide to make glucose and release oxygen.</p>'
        '<img src="leaf.png" alt="leaf">'
        + "".join(f'<h2 id="s{i}">Section {i}</h2><p>' + "Chlorophyll absorbs light. " * 40 + "</p>"
                  for i in range(1, 9)))),
    ("C++", "C++", page(
        "C++", "<p>C++ is a programming language created by Bjarne Stroustrup.</p>")),
    ("What_is_this?", "What is this?", page(
        "What is this?", "<p>An article whose path contains a question mark.</p>")),
    ("Café_au_lait", "Café au lait", page(
        "Café au lait", "<p>Coffee with hot milk; the path contains non-ASCII characters.</p>")),
]


def build(out: Path) -> None:
    out.unlink(missing_ok=True)
    with Creator(out).config_indexing(True, "eng") as creator:
        creator.set_mainpath("index")
        for path, title, html in ARTICLES:
            creator.add_item(Entry(path, title, "text/html", html, True))
        creator.add_item(Entry("style.css", "", "text/css", CSS, False))
        creator.add_item(Entry("leaf.png", "", "image/png", PNG, False))
        creator.add_redirection("Plants", "Plants", "Photosynthesis", {Hint.FRONT_ARTICLE: True})
        creator.add_illustration(48, PNG)
        for name, value in [
            ("Title", "Granger Test Library"),
            ("Description", "Tiny ZIM for Granger tests"),
            ("Language", "eng"),
            ("Creator", "Granger"),
            ("Publisher", "Granger"),
            ("Name", "granger_test_en"),
            ("Date", "2026-10-15"),
        ]:
            creator.add_metadata(name, value)


if __name__ == "__main__":
    build(Path(sys.argv[1]))
