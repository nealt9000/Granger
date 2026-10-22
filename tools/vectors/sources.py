"""Sources for pack-time vectors, read the way the phone reads them, and chunked by the app's own code.

ZIM articles: every non-redirect entry with an HTML mimetype, keyed by its path (the phone's resolved path).
EPUB chapters: the spine, resolved against the package document as EpubInspector does, keyed by the zip path.
Chunks come from `tools/chunker` (`:core:text`), so they match the phone's by construction.
"""
import base64
import json
import os
import re
import subprocess
import threading
import zipfile
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[2]
CHUNKER = ROOT / "tools/chunker/build/install/chunker/bin/chunker"
JAVA_HOME = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
XHTML_LIMIT = 16 * 1024 * 1024  # core/books Util.kt


def zim_sources(path):
    """(key, title, bytes) for every HTML article in the ZIM at [path]."""
    from libzim.reader import Archive
    archive = Archive(str(path))
    for i in range(archive.all_entry_count):
        entry = archive._get_entry_by_id(i)
        if entry.is_redirect:
            continue
        item = entry.get_item()
        if not (item.mimetype.startswith("text/html") or item.mimetype.startswith("application/xhtml")):
            continue
        yield entry.path, entry.title, bytes(item.content)


def _resolve(opf_path, href):
    """EpubInspector.resolve: the href's path (percent-decoded, as java.net.URI does), joined to the OPF's folder."""
    try:
        decoded = unquote(urlparse(href).path) if href else href
    except ValueError:
        decoded = href
    parts = [p for p in opf_path.rpartition("/")[0].split("/") if p]
    for seg in decoded.split("/"):
        if seg in ("", "."):
            continue
        if seg == "..":
            if parts:
                parts.pop()
        else:
            parts.append(seg)
    return "/".join(parts)


def _attrs(tag_text):
    return dict(re.findall(r'([\w:-]+)\s*=\s*"([^"]*)"', tag_text)) | dict(re.findall(r"([\w:-]+)\s*=\s*'([^']*)'", tag_text))


def epub_spine(z):
    """(title, [zip paths in spine order]) for an open EPUB zip."""
    container = z.read("META-INF/container.xml").decode("utf-8", "replace")
    opf_path = _attrs(re.search(r"<(?:\w+:)?rootfile\b[^>]*>", container).group(0))["full-path"]
    opf = z.read(opf_path).decode("utf-8", "replace")
    manifest = {}
    for m in re.finditer(r"<(?:\w+:)?item\b[^>]*>", opf):
        a = _attrs(m.group(0))
        if "id" in a:
            manifest.setdefault(a["id"], a.get("href", ""))
    spine = []
    for m in re.finditer(r"<(?:\w+:)?itemref\b[^>]*>", opf):
        href = manifest.get(_attrs(m.group(0)).get("idref"))
        if href is not None:
            spine.append(_resolve(opf_path, href))
    t = re.search(r"<dc:title\b[^>]*>(.*?)</dc:title>", opf, re.S)
    title = re.sub(r"\s+", " ", t.group(1)).strip() if t else ""
    return title, spine


def epub_sources(path):
    """(key, title, bytes) for every readable spine document of the EPUB at [path], as XhtmlText.all reads them."""
    with zipfile.ZipFile(path) as z:
        title, spine = epub_spine(z)
        names = set(z.namelist())
        for href in spine:
            if href not in names:
                continue
            info = z.getinfo(href)
            if info.file_size > XHTML_LIMIT:
                continue
            yield href, title or Path(path).stem, z.read(href)


def chunk(kind, sources):
    """Streams (key, title, bytes) through the chunker; yields (title, chunk dict) in order."""
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    proc = subprocess.Popen([str(CHUNKER)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env, text=True,
                            encoding="utf-8", bufsize=1 << 20)
    titles = {}
    lock = threading.Lock()

    def feed():
        try:
            for key, title, data in sources:
                with lock:
                    titles[key] = title
                proc.stdin.write(json.dumps({"kind": kind, "key": key, "bytes": base64.b64encode(data).decode()}) + "\n")
        finally:
            proc.stdin.close()

    writer = threading.Thread(target=feed, daemon=True)
    writer.start()
    for line in proc.stdout:
        c = json.loads(line)
        with lock:
            title = titles.get(c["key"], "")
        yield title, c
    writer.join()
    if proc.wait() != 0:
        raise RuntimeError("chunker exited with %d" % proc.returncode)
