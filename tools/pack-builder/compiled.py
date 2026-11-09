"""Puts the parts of a compiled work (several texts from several sources) into one body, a part to a heading.

Each part opens with an `h2` of its manifest title; a part made of `pages` gives each page an `h3` of its own.
A part's own title at its start (a heading or line that says what the part's title says) goes, since the part's
heading now says it; a run of headings at its start keeps only its last as a heading, and the ones before it
(a subtitle such as "Passed by Congress September 25, 1789") become centred lines. The part's other headings move
below the part's. Every id gets the part's number, so two parts' anchors can't collide.
"""
import re
from dataclasses import dataclass, field

from bs4 import BeautifulSoup, NavigableString, Tag

HEADING = re.compile(r"^h[1-6]$")
CSS = "p.gc-sub { text-align: center; font-style: italic; text-indent: 0; }"
_ID = re.compile(r"[^A-Za-z0-9_.-]")


@dataclass
class Part:
    title: str
    body: Tag | None = None
    pages: list["Part"] = field(default_factory=list)


def assemble(parts: list[Part]) -> Tag:
    holder = BeautifulSoup("", "lxml")
    body = holder.new_tag("div")
    for number, part in enumerate(parts, 1):
        body.append(_heading(holder, 2, part.title, f"part{number}"))
        if part.body is not None:
            body.append(_fit(holder, part.body, part.title, 3, f"p{number}-"))
        for index, page in enumerate(part.pages, 1):
            body.append(_heading(holder, 3, page.title, f"part{number}-{index}"))
            if page.body is not None:
                body.append(_fit(holder, page.body, page.title, 4, f"p{number}-{index}-"))
    return body


def _heading(soup: BeautifulSoup, level: int, text: str, ident: str) -> Tag:
    tag = soup.new_tag(f"h{level}", attrs={"id": ident})
    tag.string = text
    return tag


def _plain(text: str) -> str:
    words = re.sub(r"[^\w]+", " ", text.lower()).split()
    return " ".join(w for w in words if w not in ("the", "a", "an", "of"))


def _fit(soup: BeautifulSoup, content: Tag, title: str, level: int, prefix: str) -> Tag:
    wrapper = soup.new_tag("div", attrs={"class": "gc-part"})
    for child in list(content.contents):
        wrapper.append(child.extract())
    wanted = _plain(title)
    leading = []
    for block in _leading(wrapper):
        text = _plain(block.get_text(" "))
        if text and (wanted in text or text in wanted) and not leading:
            block.decompose()
            continue
        if not HEADING.match(block.name):
            break
        leading.append(block)
    for heading in leading[:-1]:
        heading.name = "p"
        heading.attrs = {"class": "gc-sub"}
    headings = wrapper.find_all(HEADING)
    if headings:
        top = min(int(h.name[1]) for h in headings)
        for h in headings:
            h.name = f"h{min(int(h.name[1]) - top + level, 6)}"
    for tag in wrapper.find_all(id=True):
        tag["id"] = prefix + _ID.sub("_", tag["id"])
    for link in wrapper.find_all("a", href=True):
        if link["href"].startswith("#"):
            link["href"] = "#" + prefix + _ID.sub("_", link["href"][1:])
    return wrapper


def _leading(wrapper: Tag):
    """The blocks with words at the start of the part, innermost, in reading order (a heading counts whole)."""
    for block in wrapper.find_all(["h1", "h2", "h3", "h4", "h5", "h6", "p", "div"]):
        if block.decomposed or block.find_parent(HEADING) is not None:
            continue
        if not HEADING.match(block.name) and block.find(["h1", "h2", "h3", "h4", "h5", "h6", "p", "div"]):
            continue
        if not block.get_text(strip=True):
            continue
        yield block


def text_part(html: str) -> Tag:
    """A part given as text in the manifest, as a body."""
    soup = BeautifulSoup(f"<div>{html}</div>", "lxml")
    body = soup.find("div")
    for tag in body.find_all(["script", "style", "img", "iframe", "object"]):
        tag.decompose()
    for link in body.find_all("a"):
        link.unwrap()
    return body


def select(body: Tag, selector: str) -> Tag:
    """Only the parts of a cleaned page that `selector` picks, in order; none picked fails the build."""
    chosen = body.select(selector)
    if not chosen:
        raise ValueError(f"select {selector!r} matched nothing")
    holder = BeautifulSoup("", "lxml").new_tag("div")
    for tag in chosen:
        holder.append(tag.extract())
    holder.append(NavigableString(""))
    return holder
