import zipfile, re, sys, json, collections, glob, os
from bs4 import BeautifulSoup
D='/Users/s.nealthompson/Developer/Granger/Granger/tools/eval/runs/r0-books/books/granger/'
M=json.load(open('/Users/s.nealthompson/Developer/Granger/Granger/tools/pack-builder/classics/manifest.json'))
sec={w['slug']:w['section'] for w in M['works']}
cls=collections.Counter(); clsbooks=collections.defaultdict(set)
for f in sorted(glob.glob(D+'*.epub')):
    slug=os.path.basename(f)[3:-16]
    if sec[slug]=='novels' and len(sys.argv)<2: continue
    z=zipfile.ZipFile(f)
    for n in z.namelist():
        if re.match(r'OEBPS/c\d+\.xhtml',n):
            for m in re.finditer(r'<(\w+)[^>]*class="([^"]+)"',z.read(n).decode()):
                for c in m.group(2).split():
                    k=m.group(1)+'.'+c; cls[k]+=1; clsbooks[k].add(slug)
for k,v in sorted(cls.items(), key=lambda kv:-len(clsbooks[kv[0]]))[:150]:
    print(f'{len(clsbooks[k]):4d} {v:8d} {k}')
