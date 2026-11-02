"""Static formatting audit of Granger Classics EPUBs, focused on verse.

Usage: .venv/bin/python -I audit.py [--all]   -> writes audit.json + prints tables
"""
import collections
import glob
import json
import os
import re
import sys
import zipfile

from bs4 import BeautifulSoup, NavigableString, Tag

D = '/Users/s.nealthompson/Developer/Granger/Granger/tools/eval/runs/r0-books/books/granger/'
MAN = '/Users/s.nealthompson/Developer/Granger/Granger/tools/pack-builder/classics/manifest.json'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'audit.json')
BOOK_CSS_LINES = 10  # BOOK_CSS is the first 10 lines of book.css

works = json.load(open(MAN))['works']
SEC = {w['slug']: w['section'] for w in works}

RULE = re.compile(r"([^{}]+)\{([^{}]*)\}")
INDENT_CLASS = re.compile(r"^(i|in|ind|indent|z|x)\d+(_\d+)?$|^indent\d*$|^(i|in)\d+h$", re.I)
LINENUM_CLASS = re.compile(r"^(linenum|linenumber|lnum|lineno|line-?number|ln|linenr|numb|lnr|lin)$", re.I)
CAPS_PERIOD = re.compile(r"^[A-Z0-9IVXLCDM .,'’:;—–-]+\.$")


def parse_css(css):
    rules = []
    for sel, body in RULE.findall(css):
        decl = {}
        for d in body.split(';'):
            if ':' in d:
                k, v = d.split(':', 1)
                decl[k.strip().lower()] = v.strip()
        for s in sel.split(','):
            rules.append((s.strip(), decl))
    return rules


def length_value(v):
    """Returns (number, unit) of the first length in a value, or None."""
    m = re.match(r"^\s*(-?\d*\.?\d+)\s*(%|em|rem|px|ex|pt|ch|vw)?", v or '')
    if not m:
        return None
    return float(m.group(1)), (m.group(2) or '')


def side_values(decl, side):
    """margin-left / padding-left style values, including shorthand."""
    out = {}
    for prop in ('margin', 'padding'):
        v = decl.get(f'{prop}-{side}')
        if v is None and prop in decl:
            parts = decl[prop].split()
            idx = {'top': 0, 'right': 1, 'bottom': 2, 'left': 3}[side]
            if len(parts) == 1:
                v = parts[0]
            elif len(parts) == 2:
                v = parts[idx % 2]
            elif len(parts) == 3:
                v = parts[[0, 1, 2, 1][idx]]
            elif len(parts) >= 4:
                v = parts[idx]
        if v is not None:
            out[prop] = v
    return out


def big_left(v):
    lv = length_value(v)
    if not lv:
        return False
    n, u = lv
    return (u == '%' and n >= 10) or (u in ('em', 'rem') and n >= 4) or (u == 'px' and n >= 50) or (u == 'pt' and n >= 36)


def short(s, n=160):
    s = re.sub(r'\s+', ' ', s)
    return s if len(s) <= n else s[:n] + '…'


def text_of(node):
    return node.get_text() if isinstance(node, Tag) else str(node)


def br_segments(p):
    """Splits a block's children at <br> into raw-text segments (source whitespace preserved)."""
    segs, cur = [], []
    for c in p.children:
        if isinstance(c, Tag) and c.name == 'br':
            segs.append(''.join(cur))
            cur = []
        else:
            cur.append(text_of(c))
    segs.append(''.join(cur))
    return segs


def audit_book(path):
    slug = os.path.basename(path)[3:-16]
    z = zipfile.ZipFile(path)
    css_all = z.read('OEBPS/book.css').decode()
    work_css = '\n'.join(css_all.splitlines()[BOOK_CSS_LINES:])
    rules = parse_css(work_css)
    chapters = sorted(n for n in z.namelist() if re.match(r'OEBPS/c\d+\.xhtml$', n))
    nav = BeautifulSoup(z.read('OEBPS/nav.xhtml'), 'lxml')
    nav_titles = [a.get_text() for a in nav.select('nav#toc a')]

    r = collections.Counter()
    ev = collections.defaultdict(list)   # evidence snippets
    used_classes = collections.Counter()
    seg_lengths = []      # visible length of each verse line
    rule_hits = collections.Counter()

    def evid(key, s, cap=4):
        if len(ev[key]) < cap:
            ev[key].append(short(s))

    selectors = [s for s, _ in rules]
    for ch in chapters:
        raw = z.read(ch).decode()
        soup = BeautifulSoup(raw, 'lxml')
        body = soup.body
        if body is None:
            continue
        # rule application (which work-CSS selectors match anything in this chapter)
        for s in set(selectors):
            if ':' in s and not re.search(r':(first|last|nth)-', s):
                continue
            try:
                if body.select_one(s) is not None or (s.strip() == 'body'):
                    rule_hits[s] += 1
            except Exception:
                pass
        for t in body.find_all(True):
            for c in t.get('class') or []:
                used_classes[(t.name, c)] += 1

        # --- verse patterns ---
        for p in body.find_all(['p', 'div', 'td', 'span', 'blockquote']):
            brs = p.find_all('br', recursive=False)
            if len(brs) < 2:
                continue
            segs = br_segments(p)
            vis = [' '.join(s.split()) for s in segs]
            nonempty = [v for v in vis if v]
            if not nonempty:
                continue
            avg = sum(map(len, nonempty)) / len(nonempty)
            if avg > 90:
                continue
            kind = f'{p.name}+br'
            r[f'verse_{kind}_blocks'] += 1
            r['verse_br_lines'] += len(nonempty)
            seg_lengths += [len(v) for v in nonempty]
            cls = ' '.join(p.get('class') or [])
            r[f'brblock_class:{p.name}.{cls or "-"}'] += 1
            # collapsed leading spaces (lost indentation) after a <br>
            leads = []
            for s in segs:
                s2 = s.lstrip('\n\r')
                if s2.strip():
                    leads.append((len(s2) - len(s2.lstrip(' \t')), s2))
            if leads:
                base = min(l for l, _ in leads)
                for l, s2 in leads:
                    if l - base >= 2:
                        r['collapsed_space_indent_lines'] += 1
                        evid('collapsed_space_indent', repr(s2[:60]))
            for s in segs:
                if re.search(r'\S {6,}\S', s):
                    r['collapsed_internal_space_runs'] += 1
                    evid('collapsed_internal_space', repr(s.strip()[:90]))
                m = re.match(r'^[\n\r]*(\xa0+)', s)
                if m and len(m.group(1)) >= 2:
                    r['nbsp_indent_lines'] += 1
                    evid('nbsp_indent', repr(s.strip('\n')[:60]))
                if re.search(r'(\s|\xa0){3,}\d{1,4}\s*$', s) or re.match(r'^\s*\d{1,4}(\s|\xa0){2,}\S', s):
                    r['inline_line_numbers'] += 1
                    evid('inline_line_numbers', repr(s.strip()[:80]))
            # stanza breaks as <br/><br/>
            for a, b in zip(segs, segs[1:]):
                pass
            r['double_br'] += sum(1 for v in vis[1:-1] if v == '')
        for pre in body.find_all('pre'):
            lines = pre.get_text().split('\n')
            ne = [l for l in lines if l.strip()]
            r['pre_blocks'] += 1
            r['pre_lines'] += len(ne)
            for l in ne:
                seg_lengths.append(len(l.rstrip()))
                if len(l.rstrip()) > 40:
                    r['pre_lines_over_40col'] += 1
                lead = len(l) - len(l.lstrip(' '))
                if lead >= 10:
                    r['pre_lines_indent_ge10'] += 1
            evid('pre', pre.get_text()[:120])
        # line-per-element verse
        for t in body.find_all(['p', 'div', 'span']):
            cls = t.get('class') or []
            for c in cls:
                if INDENT_CLASS.match(c):
                    r[f'indent_class_used:{t.name}.{c}'] += 1
                if LINENUM_CLASS.match(c):
                    r['linenum_elements'] += 1
                    evid('linenum', str(t)[:120])
        for sel, key in (('div.stanza', 'div_stanza'), ('div.poem', 'div_poem'), ('div.poetry', 'div_poetry'),
                         ('div.verse', 'div_verse'), ('div.tei-lg', 'tei_lg'), ('div.tei-l', 'tei_l'),
                         ('div.line', 'div_line'), ('p.poem', 'p_poem'), ('p.drama', 'p_drama'),
                         ('span.charname', 'span_charname'), ('p.scenedesc', 'p_scenedesc'), ('p.stage', 'p_stage'),
                         ('p.center', 'p_center'), ('p.right', 'p_right'), ('span.dropcap', 'dropcap'),
                         ('table', 'tables')):
            r[key] += len(body.select(sel))
        # lines in stanza/poem containers that are one element per line
        for st in body.select('div.stanza, div.tei-lg, div.poem, div.poetry, div.verse'):
            kids = [k for k in st.find_all(['p', 'div', 'span'], recursive=False)]
            for k in kids:
                tx = ' '.join(k.get_text().split())
                if tx and len(tx) < 120 and not k.find(['p', 'div']):
                    r['line_elements'] += 1
                    seg_lengths.append(len(tx))
        # tables with line numbers
        for tb in body.find_all('table'):
            tds = tb.find_all('td')
            num = sum(1 for td in tds if re.fullmatch(r'\s*\[?\d{1,4}\]?\s*', td.get_text() or ''))
            if num >= 3:
                r['tables_with_number_cells'] += 1
                evid('table_numbers', str(tb)[:200])
        # inline styles with big margins / widths / positioning
        for t in body.find_all(style=True):
            st = t['style']
            d = dict((k.strip().lower(), v.strip()) for k, v in (x.split(':', 1) for x in st.split(';') if ':' in x))
            for side in ('left', 'right'):
                for prop, v in side_values(d, side).items():
                    if big_left(v):
                        r['inline_big_margin'] += 1
                        evid('inline_big_margin', f'<{t.name} style="{st}">')
            if 'width' in d and t.name not in ('img', 'hr', 'table', 'td', 'col'):
                r['inline_width'] += 1
                evid('inline_width', f'<{t.name} style="{st}">')
            if re.search(r'position\s*:\s*absolute|float', st):
                r['inline_float_or_abs'] += 1
                evid('inline_float_abs', f'<{t.name} style="{st}">')
            if re.search(r'text-align\s*:\s*center', st):
                r['inline_center'] += 1
        # leftovers
        txt = body.get_text(' ')
        r['illustration_text'] += len(re.findall(r'\[\s*Illustration', txt, re.I))
        for m in re.finditer(r'\[\s*Illustration[^\]]{0,60}', txt, re.I):
            evid('illustration_text', m.group(0))
        for m in re.finditer(r"transcriber[’'`]?s?\s+note", txt, re.I):
            r['transcriber_note'] += 1
            evid('transcriber_note', txt[max(0, m.start() - 20):m.start() + 100])
        for t in body.find_all(['p', 'div']):
            if not t.get_text().strip().replace('\xa0', '') and not t.find(['img', 'hr', 'br', 'table']) and not t.find(True, id=True) and not t.get('id'):
                if t.name == 'p':
                    r['empty_p'] += 1
                else:
                    r['empty_div'] += 1
        for t in body.find_all(['p', 'div']):
            if t.get_text().strip() == '' and t.get_text().count('\xa0') >= 1:
                r['nbsp_only_blocks'] += 1
        if body.find('header', class_='pg-boilerplate'):
            r['pg_header_leftover'] += 1
        for h in body.find_all(re.compile('^h[1-6]$')):
            ht = ' '.join(h.get_text().split())
            if not ht:
                r['empty_heading'] += 1
                continue
            if CAPS_PERIOD.match(ht) and len(ht) > 2:
                r['heading_caps_period'] += 1
                evid('heading_caps_period', ht)
            if len(ht) > 90:
                r['heading_long'] += 1
                evid('heading_long', ht)
            if h.find('br'):
                r['heading_br'] += 1
        for t in body.find_all(['p', 'h1', 'h2', 'h3', 'h4', 'div']):
            tx = ' '.join(t.get_text().split()).lower()
            if tx in ('contents', 'contents.', 'table of contents', 'content'):
                r['contents_heading'] += 1
        r['toc_class_elems'] += len(body.select('p.toc, .toc, td.toc, ul.toc, div.toc'))
        # block of internal links to other chapters (in-book contents)
        for t in body.find_all(['table', 'ul', 'ol', 'div', 'p']):
            links = t.find_all('a', href=re.compile(r'^c\d+\.xhtml'), recursive=True)
            if len(links) >= 8 and len(t.get_text()) < 60 * len(links) and t.parent is body:
                r['inbook_toc_blocks'] += 1
                break
    # --- CSS findings ---
    css_find = []
    for sel, decl in rules:
        hit = rule_hits.get(sel, 0)
        is_body = re.fullmatch(r'(html\s*)?body', sel) is not None
        notes = []
        for side in ('left', 'right'):
            for prop, v in side_values(decl, side).items():
                if big_left(v):
                    notes.append(f'{prop}-{side}:{v}')
        w = decl.get('width')
        if w and not re.search(r'\b(img|hr|table|td|th|col)\b', sel):
            lv = length_value(w)
            if lv and lv[1] in ('px', 'em', 'pt', '%'):
                notes.append(f'width:{w}')
        if decl.get('text-align') == 'justify':
            notes.append('justify')
        if 'float' in decl:
            notes.append(f'float:{decl["float"]}')
        if decl.get('display') == 'inline-block':
            notes.append('inline-block')
        if decl.get('position') in ('absolute', 'fixed'):
            notes.append(f'position:{decl["position"]}')
        if decl.get('white-space') in ('nowrap', 'pre'):
            notes.append(f'white-space:{decl["white-space"]}')
        ti = decl.get('text-indent')
        if ti and ti.strip().startswith('-'):
            notes.append(f'hanging:{ti}')
        if notes:
            css_find.append({'sel': sel, 'notes': notes, 'chapters_matched': hit, 'body': is_body})
    # classes used with no rule at all
    css_classes = set(re.findall(r'\.([A-Za-z_][\w-]*)', work_css))
    unstyled = {f'{n}.{c}': k for (n, c), k in used_classes.items()
                if c not in css_classes and (INDENT_CLASS.match(c) or LINENUM_CLASS.match(c)
                                             or c in ('stanza', 'poem', 'poetry', 'verse', 'line', 'i0'))}
    # indent classes whose rules are descendant selectors that never match
    dead_rules = [s for s, d in rules if rule_hits.get(s, 0) == 0 and re.search(r'\.(i\d+|stanza|poem|linenum|verse)\b', s)]
    lens = sorted(seg_lengths)
    stat = {}
    if lens:
        stat = {'n': len(lens), 'median': lens[len(lens) // 2], 'p90': lens[int(len(lens) * .9)],
                'over40': sum(1 for x in lens if x > 40), 'over50': sum(1 for x in lens if x > 50)}
    nav_flags = {'n': len(nav_titles),
                 'caps_period': [t for t in nav_titles if CAPS_PERIOD.match(t.strip())][:6],
                 'long': [short(t, 100) for t in nav_titles if len(t) > 60][:4],
                 'part_n': sum(1 for t in nav_titles if re.search(r'\(part \d+\)', t))}
    return {'slug': slug, 'section': SEC.get(slug), 'chapters': len(chapters), 'counts': dict(r),
            'evidence': dict(ev), 'css': css_find, 'unstyled_classes': unstyled, 'dead_verse_rules': dead_rules,
            'line_len': stat, 'nav': nav_flags}


def main():
    allbooks = '--all' in sys.argv
    out = []
    for f in sorted(glob.glob(D + '*.epub')):
        slug = os.path.basename(f)[3:-16]
        if not allbooks and SEC[slug] == 'novels':
            continue
        out.append(audit_book(f))
        print(slug, file=sys.stderr)
    json.dump(out, open(OUT, 'w'), indent=1, ensure_ascii=False)


if __name__ == '__main__':
    main()
