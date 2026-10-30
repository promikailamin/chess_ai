import csv,json,glob,os,sys
import cv2,numpy as np,chess
sys.path.insert(0,'.agent-work/tests')
from proto4 import warp,cell_bg,CELL,TYPES,mask_cell
from proto_global_align import load_all,stats,bbox
THEMES=[os.path.basename(d) for d in sorted(glob.glob('dataset/pieces/*')) if os.path.isdir(d)]

def match(cell,ts,theme,bg,m):
 sub=[x for x in ts if x[0]==theme]
 size=sub[0][2].shape[0];ccell=cv2.resize(cell,(size,size));cbg=cv2.resize(bg,(size,size));cm=cv2.resize(m.astype(np.uint8),(size,size),interpolation=cv2.INTER_NEAREST)>0
 cb=bbox(cm);best=None
 if cb is None:return None
 cx=cb[0]+cb[2];cy=cb[1]+cb[3]
 for th,key,rgb,a,ab,ax,ay in sub:
  dx=int(round((cx-ax)/2));dy=int(round((cy-ay)/2));dx=max(-4,min(4,dx));dy=max(-4,min(4,dy))
  for rx,ry in {(0,0),(dx,dy)}:
   rr=np.roll(np.roll(rgb,ry,0),rx,1);aa=np.roll(np.roll(a,ry,0),rx,1);mm=aa>0.25
   comp=rr*aa[...,None]+cbg*(1-aa[...,None]);score=1-np.abs(ccell[mm]-comp[mm]).mean()/255.
   if best is None or score>best[0]:
    inter=np.logical_and(cm,mm).sum();union=np.logical_or(cm,mm).sum()
    best=(score,th,key,inter/max(1,cm.sum()),inter/max(1,union))
 return best

def run(cases,ts):
 total=correct=exact=0;conf={};cfail=[];wrong=[]
 for path,quad,fen,theme,cid in cases:
  img=cv2.imread(path);board=warp(img,quad);bd=chess.Board(fen)
  cells=[];bgs=[];ms=[];safe=[]
  for r in range(8):
   for c in range(8):
    cell=board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32);bg,m,_=stats(cell)
    n,lab,st,cen=cv2.connectedComponentsWithStats(m.astype(np.uint8),8);areas=[int(st[i,cv2.CC_STAT_AREA]) for i in range(1,n)]
    lr=max(areas,default=0)/max(1,m.sum());core=m[34:62,34:62].mean()
    cells.append(cell);bgs.append(bg);ms.append(m);safe.append(m.mean()>=0.02 and core>=0.02 and lr>=0.3)
  totals={t:[] for t in THEMES}
  for i in range(64):
   if not safe[i]:continue
   for t in THEMES:
    b=match(cells[i],ts,t,bgs[i],ms[i])
    if b:totals[t].append(b[0])
  chosen=max(THEMES,key=lambda t:sum(totals[t])/max(1,len(totals[t])))
  if chosen!=theme:wrong.append((cid,theme,chosen))
  ok=0
  for i in range(64):
   r,c=divmod(i,8);e=bd.piece_at((7-r)*8+c);exp=None if e is None else ('white' if e.color else 'black',TYPES['pnbrqk'.index(e.symbol().lower())])
   if ms[i].mean()<0.015:pred=None
   else:
    b=match(cells[i],ts,chosen,bgs[i],ms[i])
    if b is None:pred=None
    else:
     score,th,key,cov,iou=b
     core=ms[i][34:62,34:62].mean()
     n,lab,st,cen=cv2.connectedComponentsWithStats(ms[i].astype(np.uint8),8);areas=[int(st[i,cv2.CC_STAT_AREA]) for i in range(1,n)];lr=max(areas,default=0)/max(1,ms[i].sum())
     occ=ms[i].mean()>=0.02 and ((core>=0.02 and lr>=0.3) or (iou>=0.08 and score>=0.86))
     pred=key if occ else None
   total+=1
   if exp==pred:ok+=1;correct+=1
   else:conf[(exp,pred)]=conf.get((exp,pred),0)+1
  if ok==64:exact+=1
  else:cfail.append((cid,theme,chosen,ok))
 return total,correct,exact,conf,cfail,wrong
if __name__=='__main__':
 size=int(sys.argv[1]) if len(sys.argv)>1 else 48
 ts=load_all(size)
 rows=list(csv.DictReader(open('dataset/labels.csv')))
 cases=[(r['image'],json.loads(r['quad']),r['fen'],r['theme'],r['id']) for r in rows]
 m=json.load(open('dataset/reference_manifest.json'))
 cases+=[(r['file'],r['quad'],r['fen'],r['theme'],r['id']) for r in m['references']]
 cases.append(('.agent-work/tmp/issue11/issue.png',[[753,42],[1756,42],[1763,1043],[746,1043]],'rnbqkbnr/pppp1ppp/8/4p3/8/2N5/PPPPPPPP/R1BQKBNR w KQkq - 0 2','walnut-flat','ISSUE'))
 t,c,e,conf,cf,wrong=run(cases,ts)
 print('FINAL piece',c/t,'exact',e,'/',len(cases),'wrongtheme',len(wrong))
 for k,v in sorted(conf.items(),key=lambda kv:-kv[1])[:20]:print(' ',k,v)
 for x in cf[:20]:print(' fail',x)
 for x in wrong[:10]:print(' wrong',x)
