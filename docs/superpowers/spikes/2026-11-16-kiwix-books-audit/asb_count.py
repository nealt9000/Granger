import sys,re
sys.path.insert(0,"/Users/s.nealthompson/Developer/Granger/Granger/tools/catalog-gen")
import zim_probe as zp
U="https://lb.download.kiwix.org/zim/other/africanstorybook.org_mul_all_2025-01.zim"
r=zp.Reader(U, budget=256<<20); z=zp.Zim(r)
def page(h):
    e=z.find(h); return z.content(e).decode('utf-8','replace')
def links(s): return [(h,re.sub(r'\s+',' ',re.sub(r'<[^>]+>',' ',t)).strip()) for h,t in re.findall(r'<a[^>]+href="\./?([0-9a-f]{32})"[^>]*>(.*?)</a>',s,re.S)]
for lang,h in [("English","ef701556c9f65b46934a8e8044f0b02e"),("Kiswahili","e2de1d40ae2e5dcc827fcd9f08e9730d")]:
    s=page(h); kids=[k for k in links(s) if k[1] and k[0]!=h]
    total=0; levels=[]
    for kh,kt in kids:
        if kh=="f9d3e0e46ea25789bbed672ff6a399ed": continue
        ks=page(kh)
        if '.epub' in ks and 'Main document' in ks: total+=1; continue
        n=len({x for x in links(ks) if x[1] and x[0] not in (kh,h,"f9d3e0e46ea25789bbed672ff6a399ed")})
        levels.append((kt[:30],n)); total+=n
    print(lang,total,levels)
print("fetched MB",r.fetched>>20)
