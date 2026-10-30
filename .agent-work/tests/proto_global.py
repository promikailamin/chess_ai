import csv,json,glob,os,sys
import cv2,numpy as np,chess
sys.path.insert(0,'.agent-work/tests')
from proto4 import warp,cell_bg,CELL,TYPES,mask_cell
THEMES=[os.path.basename(d) for d in sorted(glob.glob('dataset/pieces/*')) if os.path.isdir(d)]

def load_all(size=48):
 out=[]
 for theme in THEMES:
  for col in ('white','black'):
   for typ in TYPES:
    im=cv2.imread(f'dataset/pieces/{theme}/{col}_{typ}.png',cv2.IMREAD_UNCHANGED)
    if im is None:continue
    rgb=im[:,:,2::-1].astype(np.float32);a=im[:,:,3].astype(np.float32)/255.
    if size!=96:
     rgb=cv2.resize(rgb,(size,size));a=cv2.resize(a,(size,size))
    out.append((theme,(col,typ),rgb,a))
 return out

def stats(cell):
 bg=cell_bg(cell,mode='ring');m=mask_cell(cell,bg)
 n,lab,st,cen=cv2.connectedComponentsWithStats(m.astype(np.uint8),8);areas=[int(st[i,cv2.CC_STAT_AREA]) for i in range(1,n)]
 lr=max(areas,default=0)/max(1,m.sum())
 occ=m.mean()>=0.02 and m[34:62,34:62].mean()>=0.02 and lr>=0.3
 return bg,m,occ

def classify(cell,ts,bg):
 size=ts[0][2].shape[0]
 if cell.shape[0]!=size:
  cell=cv2.resize(cell,(size,size));bg=cv2.resize(bg,(size,size))
 best=None
 for theme,key,rgb,a in ts:
  comp=rgb*a[...,None]+bg*(1-a[...,None]);m=a>0.25
  score=1-np.abs(cell[m]-comp[m]).mean()/255.
  if best is None or score>best[0]:best=(score,theme,key)
 return best

def run(cases,ts):
 total=correct=exact=0;conf={};texp={};cfail=[]
 cache={}
 for path,quad,fen,theme,cid in cases:
  img=cv2.imread(path);board=warp(img,quad);bd=chess.Board(fen);ok=0
  for r in range(8):
   for c in range(8):
    cell=board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32)
    bg,m,occ=stats(cell)
    e=bd.piece_at((7-r)*8+c)
    exp=None if e is None else ('white' if e.color else 'black',TYPES['pnbrqk'.index(e.symbol().lower())])
    if not occ:pred=None
    else:
     b=classify(cell,ts,bg);pred=b[2] if b else None
     if b and b[1]!=theme:texp[(theme,b[1])]=texp.get((theme,b[1]),0)+1
    total+=1
    if exp==pred:ok+=1;correct+=1
    else:conf[(exp,pred)]=conf.get((exp,pred),0)+1
  if ok==64:exact+=1
  else:cfail.append((cid,theme,ok))
 return total,correct,exact,conf,texp,cfail

if __name__=='__main__':
 size=int(sys.argv[1]) if len(sys.argv)>1 else 48
 ts=load_all(size)
 rows=list(csv.DictReader(open('dataset/labels.csv')))
 cases=[(r['image'],json.loads(r['quad']),r['fen'],r['theme'],r['id']) for r in rows]
 m=json.load(open('dataset/reference_manifest.json'))
 cases+=[(r['file'],r['quad'],r['fen'],r['theme'],r['id']) for r in m['references']]
 cases.append(('.agent-work/tmp/issue11/issue.png',[[753,42],[1756,42],[1763,1043],[746,1043]],'rnbqkbnr/pppp1ppp/8/4p3/8/2N5/PPPPPPPP/R1BQKBNR w KQkq - 0 2','walnut-flat','ISSUE'))
 t,c,e,conf,texp,cf=run(cases,ts)
 print('global size',size,'piece',c/t,'exact',e,'/',len(cases))
 print('theme confusions',sorted(texp.items(),key=lambda kv:-kv[1])[:10])
 for k,v in sorted(conf.items(),key=lambda kv:-kv[1])[:20]:print(' ',k,v)
 for x in cf[:10]:print(' fail',x)
