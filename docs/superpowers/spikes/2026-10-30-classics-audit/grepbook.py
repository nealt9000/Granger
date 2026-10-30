import zipfile,re,sys
D='/Users/s.nealthompson/Developer/Granger/Granger/tools/eval/runs/r0-books/books/granger/'
slug,pat=sys.argv[1],sys.argv[2]; n=int(sys.argv[3]) if len(sys.argv)>3 else 2; ctx=int(sys.argv[4]) if len(sys.argv)>4 else 300
z=zipfile.ZipFile(f'{D}gc-{slug}_2026-10-17.epub'); k=0
if pat=='CSS': print(z.read('OEBPS/book.css').decode().split('\n',10)[10]); sys.exit()
for nm in sorted(z.namelist()):
    if not re.match(r'OEBPS/c\d+\.xhtml',nm): continue
    t=z.read(nm).decode()
    for m in re.finditer(pat,t):
        print(f'--- {nm} @{m.start()}'); print(t[max(0,m.start()-80):m.start()+ctx]); k+=1
        if k>=n: sys.exit()
