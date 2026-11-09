"""A small Standard Ebooks "compatible" EPUB, laid out as SE builds them, for the importer's tests."""
import io
import zipfile

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml" '
        'xmlns:epub="http://www.idpf.org/2007/ops" epub:prefix="z3998: http://www.daisy.org/z3998/2012/vocab/structure/, '
        'se: https://standardebooks.org/vocab/1.0" lang="en-GB" xml:lang="en-GB"><head><title>{title}</title>'
        '<link href="../css/core.css" rel="stylesheet" type="text/css"/></head>')

FILES = {
    "titlepage.xhtml": '<body epub:type="frontmatter"><section id="titlepage" epub:type="titlepage"><h1 epub:type="title">'
                       'The Test Play</h1><img alt="" src="../images/titlepage.svg" epub:type="se:image.color-depth.black-on-transparent"/>'
                       '</section></body>',
    "imprint.xhtml": '<body epub:type="frontmatter"><section id="imprint" epub:type="imprint"><header><h2 epub:type="title">'
                     'Imprint</h2></header><p>This ebook is the product of many hours of hard work by volunteers for '
                     '<a href="https://standardebooks.org/">Standard Ebooks</a>.</p></section></body>',
    "preface.xhtml": '<body epub:type="frontmatter"><section id="preface" role="doc-preface" epub:type="preface">'
                     '<h2 epub:type="title">Preface</h2><p>A word﻿—and a note.<a href="endnotes.xhtml#note-1" '
                     'id="noteref-1" role="doc-noteref" epub:type="noteref">1</a> See <a href="act-1.xhtml">the first act</a>'
                     ' and <a href="https://example.org/">elsewhere</a>.</p>'
                     '<figure id="f1"><img alt="A drawing." src="../images/f1.png"/><figcaption>A drawing.</figcaption></figure>'
                     '</section></body>',
    "act-1.xhtml": '<body class="epub-type-contains-word-z3998-drama" epub:type="bodymatter z3998:drama z3998:fiction">'
                   '<section id="act-1" epub:type="chapter z3998:scene"><hgroup><h2><span epub:type="se:label">Act</span> '
                   '<span epub:type="z3998:roman">I</span></h2><p epub:type="title">The Garden</p></hgroup>'
                   '<table><tbody>'
                   '<tr><td/><td><i epub:type="z3998:stage-direction"><b epub:type="z3998:persona">Lane</b> enters.</i></td></tr>'
                   '<tr><td epub:type="z3998:persona">Algernon</td><td>Did you hear? <i epub:type="z3998:stage-direction">'
                   'Sits.</i> Well.</td></tr>'
                   '<tr><td epub:type="z3998:persona">Lane</td><td epub:type="z3998:verse"><p><span>First line,</span><br/>'
                   '<span class="i1">and the second.</span></p></td></tr>'
                   '</tbody></table>'
                   '<p><i epub:type="z3998:stage-direction">Curtain.</i></p></section></body>',
    "poem.xhtml": '<body epub:type="bodymatter"><article id="poem" epub:type="z3998:poem"><h2 epub:type="title">A Song</h2>'
                  '<p><span>Sing high,</span><br/><span class="i2">sing low.</span></p></article></body>',
    "endnotes.xhtml": '<body epub:type="backmatter"><section id="endnotes" role="doc-endnotes" epub:type="endnotes">'
                      '<h2 epub:type="title">Endnotes</h2><ol><li id="note-1" epub:type="endnote"><p>The note. '
                      '<a href="preface.xhtml#noteref-1" role="doc-backlink" epub:type="backlink">↩︎</a></p></li>'
                      '</ol></section></body>',
    "colophon.xhtml": '<body epub:type="backmatter"><section id="colophon" epub:type="colophon"><h2 epub:type="title">'
                      'Colophon</h2><p>The Test Play was published in 1926 by A. Writer. It was translated from German '
                      'in 1929 by B. Writer.</p><p>This ebook was produced for Standard Ebooks by C. Producer. This '
                      'edition was released on October 30, 2026.</p></section></body>',
    "uncopyright.xhtml": '<body epub:type="backmatter"><section id="uncopyright" epub:type="copyright-page">'
                         '<h2 epub:type="title">Uncopyright</h2><p>May you do good and not evil.</p></section></body>',
}
SPINE = ["titlepage", "imprint", "preface", "act-1", "poem", "endnotes", "colophon", "uncopyright"]
RIGHTS = ("The source text and artwork in this ebook are believed to be in the United States public domain; that "
          "is, they are believed to be free of copyright restrictions in the United States. The creators of, and "
          "contributors to, this ebook dedicate their contributions to the worldwide public domain via the terms in "
          "the [CC0 1.0 Universal Public Domain Dedication](https://creativecommons.org/publicdomain/zero/1.0/).")
OPF = ('<?xml version="1.0" encoding="utf-8"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" '
       'unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
       '<dc:identifier id="uid">https://standardebooks.org/ebooks/a-writer/the-test-play/b-writer</dc:identifier>'
       '<dc:title id="title">The Test Play</dc:title><dc:rights>' + RIGHTS + '</dc:rights>'
       '<dc:creator id="author">A. Writer</dc:creator>'
       '<meta property="role" refines="#author" scheme="marc:relators">aut</meta>'
       '<dc:contributor id="translator">B. Writer</dc:contributor>'
       '<meta property="role" refines="#translator" scheme="marc:relators">trl</meta>'
       '<dc:contributor id="producer">C. Producer</dc:contributor>'
       '<meta property="role" refines="#producer" scheme="marc:relators">bkp</meta>'
       '</metadata><manifest>'
       '<item href="css/local.css" id="local.css" media-type="text/css"/>'
       + "".join(f'<item href="text/{n}.xhtml" id="{n}.xhtml" media-type="application/xhtml+xml"/>' for n in SPINE)
       + '</manifest><spine>' + "".join(f'<itemref idref="{n}.xhtml"/>' for n in SPINE) + '</spine></package>')
LOCAL_CSS = ('@charset "utf-8";\n@namespace epub "http://www.idpf.org/2007/ops";\n\n'
             'section.epub-type-contains-word-z3998-scene > p{\n\tmargin-left: auto;\n}\n'
             '[epub|type~="z3998:persona"]{\n\tfont-variant: small-caps;\n}\n')


def epub(extra: dict[str, str] | None = None, opf: str = OPF) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("META-INF/container.xml",
                   '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                   '<rootfiles><rootfile full-path="epub/content.opf" media-type="application/oebps-package+xml"/>'
                   '</rootfiles></container>')
        z.writestr("epub/content.opf", opf)
        z.writestr("epub/css/local.css", LOCAL_CSS)
        for name, body in FILES.items():
            z.writestr(f"epub/text/{name}", HEAD.format(title=name) + body + "</html>")
        for name, data in (extra or {}).items():
            z.writestr(name, data)
    return buffer.getvalue()


PAGE = ('<html><body><section id="download"><a href="/ebooks/a-writer/the-test-play/b-writer/downloads/'
        'a-writer_the-test-play_b-writer.epub">Compatible epub</a> <a href="/ebooks/a-writer/the-test-play/b-writer/'
        'downloads/a-writer_the-test-play_b-writer_advanced.epub">Advanced epub</a></section></body></html>').encode()
PLACEHOLDER = ('<html><body><article class="placeholder" typeof="schema:Book"><p>This book enters the public domain '
               'in 2035.</p></article></body></html>').encode()
