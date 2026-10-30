import csv,json,glob,os,sys
import cv2,numpy as np,chess
sys.path.insert(0,'.agent-work/tests')
from proto4 import warp,cell_bg,CELL,TYPES,mask_cell
from proto_global_align import load_all,stats,bbox
THEMES=[os.path.basename(d) for d in sorted(glob.glob('dataset/pieces/*')) if os.path.isdir(d)]

def match(cell,ts,bg,m,theme):
 size=ts[0][2].shape[0];ccell=cv2.resize(cell,(size,size));cbg=cv2.resize(bg,(size,size));cm=cv2.resize(m.astype(np.uint8),(size,size),interpolation=cv2.INTER_NEAREST)>0
 cb=bbox(cm)
 if cb is None:return None
 cx=cb[0]+cb[2];cy=cb[1]+cb[3];best=None
 for th,key,rgb,a,ab,ax,ay in ts:
  if th!=theme:continue
  dx=int(round((cx-ax)/2));dy=int(round((cy-ay)/2));dx=max(-4,min(4,dx));dy=max(-4,min(4,dy))
  for rx,ry in {(0,0),(dx,dy)}:
   rr=np.roll(np.roll(rgb,ry,0),rx,1);aa=np.roll(np.roll(a,ry,0),rx,1);mm=aa>0.25
   comp=rr*aa[...,None]+cbg*(1-aa[...,None]);score=1-np.abs(ccell[mm]-comp[mm]).mean()/255.
   if best is None or score>best[0]:best=(score,th,key,rx,ry,aa>0.25)
 return best

def run(cases,ts):
 feats=[]
 for path,quad,fen,theme,cid in cases:
  img=cv2.imread(path);board=warp(img,quad);bd=chess.Board(fen)
  for r in range(8):
   for c in range(8):
    cell=board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32);bg,m,occ=stats(cell)
    e=bd.piece_at((7-r)*8+c);piece=e is not None
    b=match(cell,ts,bg,m,theme)
    f=dict(piece=piece,area=float(m.mean()),core=float(m[34:62,34:62].mean()),lr=0.0,score=0,coverage=0,iou=0,cid=cid,theme=theme)
    if b is not None:
     score,th,key,rx,ry,aa=b;f['score']=float(score)
     # shift mask same as template
     cm=cv2.resize(m.astype(np.uint8),(aa.shape[0],aa.shape[1]),interpolation=cv2.INTER_NEAREST)>0
     # align cm with template alpha: template shifted by rx,ry; compare cm with aa shifted? already aa is shifted, cm not
     inter=np.logical_and(cm,aa).sum();union=np.logical_or(cm,aa).sum()
     f['coverage']=inter/max(1,cm.sum());f['iou']=inter/max(1,union)
    n,lab,st,cen=cv2.connectedComponentsWithStats(m.astype(np.uint8),8);areas=[int(st[i,cv2.CC_STAT_AREA]) for i in range(1,n)]
    f['lr']=max(areas,default=0)/max(1,m.sum())
    feats.append(f)
 return feats
if __name__=='__main__':
 ts=load_all(48)
 rows=list(csv.DictReader(open('dataset/labels.csv')))
 cases=[(r['image'],json.loads(r['quad']),r['fen'],r['theme'],r['id']) for r in rows]
 m=json.load(open('dataset/reference_manifest.json'))
 cases+=[(r['file'],r['quad'],r['fen'],r['theme'],r['id']) for r in m['references']]
 cases.append(('.agent-work/tmp/issue11/issue.png',[[753,42],[1756,42],[1763,1043],[746,1043]],'rnbqkbnr/pppp1ppp/8/4p3/8/2N5/PPPPPPPP/R1BQKBNR w KQkq - 0 2','walnut-flat','ISSUE'))
 fs=run(cases,ts)
 import pickle;pickle.dump(fs,open('.agent-work/tmp/occfeat.pkl','wb'))
 for label,cond in [('empty',lambda f:not f['piece']),('piece',lambda f:f['piece'])]:
  a=[f for f in fs if cond(f)]
  print(label,len(a))
  for k in ['area','core','lr','score','coverage','iou']:
   print(' ',k,np.percentile([f[k] for f in a],[0,1,5,10,25,50,75,90,95,99,100]))
