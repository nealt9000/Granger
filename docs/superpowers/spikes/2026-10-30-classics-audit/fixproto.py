"""Prototype of the proposed builder rules, applied to a chapter soup + the book CSS, for re-measuring.

verse(soup)      -> what epubpack._prepare would do (lines to spans, pre to verse divs, alignment boost data)
scrub_extra(css) -> what scrub_css would add (clamp % side margins, drop absolute positioning)
VERSE_CSS        -> what write_epub would append after the work's CSS
"""
import re

from bs4 import NavigableString, Tag

VERSE_CSS = """
/* Granger verse rules (prototype) */
.gv { text-align: start; text-indent: 0; hyphens: manual; -webkit-hyphens: manual; }
p.gv, div.gv, td.gv { margin-left: 0; margin-right: 0; }  /* verse sections only */
.gv-l { display: block; padding-left: 1.5em; text-indent: -1.5em; }
.gv-gap { display: block; height: .75em; }
.gv-n { float: right; font-size: .75em; opacity: .65; margin-left: .75em; text-indent: 0; font-style: normal; }
.gv [class*=dropcap] { float: none; font-size: inherit; line-height: inherit; margin: 0; }
div.stanza > p, div.stanza > div, div.poem > p, div.poetry > p, div.verse > p, div.tei-l, div.line {
  padding-left: 1.5em; text-indent: -1.5em; }
"""

BLOCKS = {'p', 'div', 'table', 'ul', 'ol', 'dl', 'blockquote', 'pre', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr'}
LEAD = re.compile(r'^[\r\n]*([ \t\xa0]*)')
TRAIL_NUM = re.compile(r'[ \t\xa0]{3,}(\[?\d{1,4}\]?)\s*$')
GAP = re.compile(r'(\S)[ \t]{6,}(\S.*)$', re.S)


def _segments(block):
    groups, cur = [], []
    for c in list(block.children):
        if isinstance(c, Tag) and c.name == 'br':
            groups.append(cur)
            c.extract()
            cur = []
        else:
            cur.append(c)
    groups.append(cur)
    return groups


def _text(nodes):
    return ''.join(n.get_text() if isinstance(n, Tag) else str(n) for n in nodes)


def is_verse_block(p):
    brs = p.find_all('br', recursive=False)
    if len(brs) < 2 or p.find(BLOCKS) is not None:
        return False
    vis = []
    cur = []
    for c in p.children:
        if isinstance(c, Tag) and c.name == 'br':
            vis.append(' '.join(_text(cur).split())); cur = []
        else:
            cur.append(c)
    vis.append(' '.join(_text(cur).split()))
    vis = [v for v in vis if v]
    return bool(vis) and sum(map(len, vis)) / len(vis) <= 90


def _indent_em(n_spaces, per=0.3):
    return f'min({n_spaces * per:.2f}em, 35%)'


def _line(soup, nodes, base_lead, per):
    """One verse line from its nodes: leading blanks -> margin, trailing number / column gap -> right-floated note."""
    span = soup.new_tag('span', attrs={'class': 'gv-l'})
    nodes = [n.extract() if getattr(n, 'parent', None) is not None else n for n in nodes]
    # strip leading whitespace in the first text nodes
    lead = 0
    while nodes and isinstance(nodes[0], NavigableString):
        s = str(nodes[0])
        m = LEAD.match(s)
        lead += len(m.group(1).replace('\t', '    '))
        rest = s[m.end():]
        if rest:
            nodes[0] = NavigableString(rest)
            break
        nodes.pop(0)
    note = None
    if nodes and isinstance(nodes[-1], NavigableString):
        s = str(nodes[-1])
        m = TRAIL_NUM.search(s)
        if m and s[:m.start()].strip():
            note = m.group(1)
            nodes[-1] = NavigableString(s[:m.start()])
        else:
            g = GAP.search(s.rstrip())
            if g:
                cut = g.start() + 1
                note = s[cut:].strip()
                nodes[-1] = NavigableString(s[:cut])
    extra = max(0, lead - base_lead)
    if extra >= 2:
        span['style'] = f'margin-left: {_indent_em(extra, per)}'
    if note:
        n = soup.new_tag('span', attrs={'class': 'gv-n'})
        n.string = note
        span.append(n)
    for n in nodes:
        span.append(n.extract() if getattr(n, 'parent', None) is not None else n)
    return span


def verse(soup):
    stats = {'br_blocks': 0, 'pre_blocks': 0}
    for p in list(soup.find_all(['p', 'div', 'td', 'blockquote'])):
        if not is_verse_block(p):
            continue
        groups = _segments(p)
        leads = []
        for g in groups:
            t = _text(g).lstrip('\r\n')
            if t.strip():
                leads.append(len(t) - len(t.lstrip(' \t\xa0')))
        base = min(leads) if leads else 0
        # source-formatting spaces (not nbsp) common to all lines are the base; nbsp always counts
        new = []
        for g in groups:
            if not _text(g).strip():
                new.append(soup.new_tag('span', attrs={'class': 'gv-gap'}))
                for n in g:
                    if getattr(n, 'parent', None) is not None:
                        n.extract()
                continue
            t = _text(g).lstrip('\r\n')
            new.append(_line(soup, list(g), base if '\xa0' not in t[:base + 1] else 0, 0.3))
        # drop gaps at the ends
        while new and new[0]['class'] == ['gv-gap']:
            new.pop(0)
        while new and new[-1]['class'] == ['gv-gap']:
            new.pop()
        for c in list(p.children):
            if isinstance(c, NavigableString) and not c.strip():
                c.extract()
        for n in new:
            p.append(n)
        p['class'] = (p.get('class') or []) + ['gv']
        stats['br_blocks'] += 1
    for pre in list(soup.find_all('pre')):
        text = pre.get_text()
        lines = text.split('\n')
        nonempty = [l for l in lines if l.strip()]
        if not nonempty:
            pre.decompose()
            continue
        # split the pre's children into lines at newlines, keeping inline tags
        groups, cur = [], []
        for c in list(pre.children):
            if isinstance(c, NavigableString):
                parts = str(c).split('\n')
                for i, part in enumerate(parts):
                    if i:
                        groups.append(cur); cur = []
                    if part:
                        cur.append(NavigableString(part))
                c.extract()
            else:
                cur.append(c.extract())
        groups.append(cur)
        base = min(len(l) - len(l.lstrip(' ')) for l in nonempty)
        div = soup.new_tag('div', attrs={'class': (pre.get('class') or []) + ['gv', 'gv-pre']})
        if pre.get('id'):
            div['id'] = pre['id']
        out = []
        for g in groups:
            if not _text(g).strip():
                if out and out[-1]['class'] != ['gv-gap']:
                    out.append(soup.new_tag('span', attrs={'class': 'gv-gap'}))
                continue
            out.append(_line(soup, g, base, 0.5))
        while out and out[-1]['class'] == ['gv-gap']:
            out.pop()
        for n in out:
            div.append(n)
        pre.replace_with(div)
        stats['pre_blocks'] += 1
    for d in soup.select('.gv span.dropcap, .gv span[class*=dropcap]'):
        d.unwrap()
    # inline text-align on p: make it win over Readium's forced alignment
    for p in soup.find_all('p', style=re.compile(r'text-align\s*:\s*(center|right|end)')):
        p['style'] = re.sub(r'(text-align\s*:\s*(center|right|end))(\s*!important)?', r'\1 !important', p['style'])
    return stats


SIDE = re.compile(r'((?:margin|padding)-(?:left|right))\s*:\s*(\d*\.?\d+)%')
RULE = re.compile(r"([^{}]+)\{([^{}]*)\}")


def scrub_extra(css):
    """Clamp % side margins, drop absolute positioning, boost p alignment over Readium's, pre to the body font."""
    out = []
    boost = []
    for sel, body in RULE.findall(css):
        decl = [d.strip() for d in body.split(';') if d.strip()]
        new = []
        has_abs = any(re.match(r'position\s*:\s*(absolute|fixed)', d) for d in decl)
        for d in decl:
            k, _, v = d.partition(':')
            k = k.strip().lower(); v = v.strip()
            if has_abs and k in ('position', 'left', 'right', 'top', 'bottom'):
                continue
            m = re.fullmatch(r'(\d*\.?\d+)%', v)
            if k in ('margin-left', 'margin-right', 'padding-left', 'padding-right') and m and float(m.group(1)) > 5:
                v = f'min({v}, 2em)'
            if k in ('margin', 'padding'):
                parts = v.split()
                v = ' '.join(f'min({x}, 2em)' if re.fullmatch(r'\d*\.?\d+%', x) and float(x[:-1]) > 5 and i % 2 == 1
                             or (len(parts) == 1 and re.fullmatch(r'\d*\.?\d+%', x) and float(x[:-1]) > 5) else x
                             for i, x in enumerate(parts))
            if k == 'text-align' and v in ('center', 'right', 'end'):
                for s in sel.split(','):
                    s = s.strip()
                    if re.search(r'(^|[\s>+~])p([.#:\[]|$)', s) or re.fullmatch(r'\.[\w-]+', s):
                        boost.append(f':root:root:root:root {s} {{ text-align: {v} !important }}')
            new.append(f'{k}: {v}')
        if has_abs:
            new.append('float: right; font-size: .75em; opacity: .65; margin-left: .75em')
        if new:
            out.append(f'{sel.strip()} {{ {"; ".join(new)} }}')
    return '\n'.join(out) + '\n' + '\n'.join(boost)


PRE_CSS = 'pre { font-family: inherit; }\n'
