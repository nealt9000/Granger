import zipfile, re, sys, glob, os, collections
D='/Users/s.nealthompson/Developer/Granger/Granger/tools/eval/runs/r0-books/books/granger/'
pats=sys.argv[1:]
for f in sorted(glob.glob(D+'*.epub')):
    z=zipfile.ZipFile(f); slug=os.path.basename(f)[3:-16]
    txt=''.join(z.read(n).decode() for n in z.namelist() if re.match(r'OEBPS/c\d+\.xhtml',n))
    hits={p:len(re.findall(p,txt)) for p in pats}
    if any(hits.values()): print(slug, hits)
