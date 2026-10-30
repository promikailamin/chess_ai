import csv,json,os,sys
import cv2,numpy as np
from PIL import Image
TYPES=['pawn','knight','bishop','rook','queen','king']
TYPE={n:i+1 for i,n in enumerate(TYPES)}
COL={'white':0,'black':1}
BOARD=768; CELL=96

def warp(img,quad):
 src=np.float32(quad); dst=np.float32([[0,0],[BOARD,0],[BOARD,BOARD],[0,BOARD]])
 return cv2.warpPerspective(img,cv2.getPerspectiveTransform(src,dst),(BOARD,BOARD))

def load_template(theme,color,typ):
 p=f'dataset/pieces/{theme}/{color}_{typ}.png'
 im=cv2.imread(p,cv2.IMREAD_UNCHANGED)
 if im is None: return None
 return im[:,:,:3].astype(np.float32), im[:,:,3].astype(np.float32)/255.0

def bg_from_cells(cells,bgmask=None):
 out={}
 for parity in (0,1):
  vals=[cells[r*8+c] for r in range(8) for c in range(8) if ((r+c)&1)==parity]
  st=np.stack(vals)
  out[parity]=np.median(st,axis=0).astype(np.float32)
 return out

def classify_cell(cell,bg,templates,mode='mse_rgba'):
 best=None
 for key,(rgb,a) in templates.items():
  comp=rgb*a[...,None]+bg*(1-a[...,None])
  # focus on union alpha + diff mask
  diff=np.abs(cell-bg).max(axis=2)
  m=(a>0.25)|(diff>24)
  if m.sum()<80: continue
  if mode.startswith('mse'):
   val=np.abs(cell[m]-comp[m]).mean()
   score=1-val/255
  elif mode.startswith('corr'):
   # grayscale corr
   cg=cell.mean(axis=2); pg=comp.mean(axis=2)
   c=cg[m]; p=pg[m]
   c=c-c.mean();p=p-p.mean()
   den=(np.linalg.norm(c)*np.linalg.norm(p))
   score=(c*p).sum()/den if den>1e-6 else 0
  elif mode.startswith('color'):
   # mean color difference over template alpha
   m2=a>0.25
   val=np.abs(cell[m2]-comp[m2]).mean()
   score=1-val/255
  if best is None or score>best[0]: best=(score,key)
 return best

def mask_cell(cell,bg):
 diff=np.abs(cell-bg).max(axis=2)
 lum=lambda x: .299*x[...,0]+.587*x[...,1]+.114*x[...,2]
 lumdiff=np.abs(lum(cell)-lum(bg))
 m=((diff>26)|(lumdiff>30)).astype(np.uint8)
 m[:3]=0;m[-3:]=0;m[:,:3]=0;m[:,-3:]=0
 m=cv2.morphologyEx(m,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
 m=cv2.morphologyEx(m,cv2.MORPH_CLOSE,np.ones((5,5),np.uint8))
 return m.astype(bool)

def run(rows, oracle_theme=True, mode='mse_rgba', allow_empty=True):
 theme_cache={}
 total=0;correct=0;conf={}
 exact=0; details=[]
 for row in rows:
  img=cv2.imread(row['image'])
  board=warp(img,json.loads(row['quad']))
  cells=[board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32) for r in range(8) for c in range(8)]
  bgs=bg_from_cells(cells)
  theme=row['theme']
  if theme not in theme_cache:
   ts={}
   for col in ('white','black'):
    for typ in TYPES:
     t=load_template(theme,col,typ)
     if t: ts[(col,typ)]=t
   theme_cache[theme]=ts
  templates=theme_cache[theme]
  # expected chars
  import chess
  bd=chess.Board(row['fen'])
  exp={}
  for sq in chess.SQUARES:
   pc=bd.piece_at(sq)
   exp[(7-chess.square_rank(sq),chess.square_file(sq))]=pc
  pred={}
  ok=0
  for r in range(8):
   for c in range(8):
    cell=cells[r*8+c]; parity=(r+c)&1; bg=bgs[parity]
    m=mask_cell(cell,bg)
    # central occupancy check
    central=m[24:72,24:72].mean()
    if central<0.05 or m.mean()<0.02:
     pred[(r,c)]=None
    else:
     b=classify_cell(cell,bg,templates,mode)
     pred[(r,c)]=b[1] if b else None
    e=exp[(r,c)]
    pe=None if e is None else ('white' if e.color else 'black', TYPES['pnbrqk'.index(e.symbol().lower())])
    pp=pred[(r,c)]
    total+=1
    if pe==pp: ok+=1;correct+=1
    else: conf[(pe,pp)]=conf.get((pe,pp),0)+1
  if ok==64: exact+=1
  else: details.append((row['id'],row['theme'],ok,row['fen'],row['image']))
 return total,correct,exact,conf,details

if __name__=='__main__':
 rows=list(csv.DictReader(open('dataset/labels.csv')))
 mode=sys.argv[1] if len(sys.argv)>1 else 'mse_rgba'
 total,correct,exact,conf,details=run(rows,mode=mode)
 print(mode,total,correct,correct/total,exact,len(rows))
 from collections import Counter
 for k,v in sorted(conf.items(),key=lambda kv:-kv[1])[:20]: print(' ',k,v)
 for d in details[:10]: print(' ',d)
