import csv,json,os,sys,pickle
import cv2,numpy as np,chess
TYPES=['pawn','knight','bishop','rook','queen','king']
BOARD=768; CELL=96

def warp(img,quad):
 src=np.float32(quad); dst=np.float32([[0,0],[BOARD,0],[BOARD,BOARD],[0,BOARD]])
 return cv2.warpPerspective(img,cv2.getPerspectiveTransform(src,dst),(BOARD,BOARD))

def load_templates(theme):
 ts={}
 for col in ('white','black'):
  for typ in TYPES:
   im=cv2.imread(f'dataset/pieces/{theme}/{col}_{typ}.png',cv2.IMREAD_UNCHANGED)
   if im is not None: ts[(col,typ)]=(im[:,:,2::-1].astype(np.float32), im[:,:,3].astype(np.float32)/255.0)
 return ts

def cell_bg(cell, margin=8, mode='ring'):
 # collect border ring pixels inside margin..margin+thick
 th=6
 inner=cell[margin:CELL-margin,margin:CELL-margin]
 if mode=='ring':
  m=np.ones((CELL,CELL),bool); m[margin:CELL-margin,margin:CELL-margin]=False
  # remove outermost grid-line border
  m[:3]=False;m[-3:]=False;m[:,:3]=False;m[:,-3:]=False
  pix=cell[m]
 elif mode=='corners':
  k=14
  pix=np.concatenate([cell[:k,:k].reshape(-1,3),cell[:k,-k:].reshape(-1,3),cell[-k:,:k].reshape(-1,3),cell[-k:,-k:].reshape(-1,3)])
 elif mode=='border_median':
  pix=cell[margin:margin+th].reshape(-1,3)
  pix=np.concatenate([pix,cell[-margin-th:-margin].reshape(-1,3),cell[:,margin:margin+th].reshape(-1,3),cell[:,-margin-th:-margin].reshape(-1,3)])
 med=np.median(pix,axis=0)
 return np.broadcast_to(med,(CELL,CELL,3)).astype(np.float32)

def sim(cell,target,mask=None):
 if mask is None: mask=np.ones(cell.shape[:2],bool)
 if mask.sum()==0:return 0
 return 1-np.abs(cell[mask]-target[mask]).mean()/255.0

def mask_cell(cell,bg):
 diff=np.abs(cell-bg).max(axis=2)
 lum=lambda x: .299*x[...,0]+.587*x[...,1]+.114*x[...,2]
 m=((diff>30)|(np.abs(lum(cell)-lum(bg))>34)).astype(np.uint8)
 m[:4]=0;m[-4:]=0;m[:,:4]=0;m[:,-4:]=0
 m=cv2.morphologyEx(m,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
 m=cv2.morphologyEx(m,cv2.MORPH_CLOSE,np.ones((5,5),np.uint8))
 return m.astype(bool)

def extract(mode):
 rows=list(csv.DictReader(open('dataset/labels.csv'))); cache={}; data=[]
 for row in rows:
  img=cv2.imread(row['image']); board=warp(img,json.loads(row['quad']))
  cells=[board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32) for r in range(8) for c in range(8)]
  theme=row['theme']; ts=cache.setdefault(theme,load_templates(theme))
  bd=chess.Board(row['fen']); exp={}
  for sq in chess.SQUARES:
   pc=bd.piece_at(sq); exp[(7-chess.square_rank(sq),chess.square_file(sq))]=None if pc is None else ('white' if pc.color else 'black',TYPES['pnbrqk'.index(pc.symbol().lower())])
  cellsout=[]
  for r in range(8):
   for c in range(8):
    cell=cells[r*8+c];bg=cell_bg(cell,mode=mode);m=mask_cell(cell,bg)
    empty=sim(cell,bg); central=m[24:72,24:72].mean(); area=m.mean(); best=None
    for key,(rgb,a) in ts.items():
     comp=rgb*a[...,None]+bg*(1-a[...,None]);score=sim(cell,comp,a>0.2)
     if best is None or score>best[0]:best=(score,key)
    cellsout.append((empty,best[0],best[1],central,area))
  data.append((row['id'],theme,exp,cellsout))
 return data

def score(data,margin,central_min,area_min):
 total=correct=exact=0;conf={}
 for pid,theme,exp,cellsout in data:
  ok=0
  for i,(empty,bscore,key,central,area) in enumerate(cellsout):
   r,c=divmod(i,8);pred=None if central<central_min or area<area_min else key
   e=exp[(r,c)];total+=1
   if e==pred:ok+=1;correct+=1
   else:conf[(e,pred)]=conf.get((e,pred),0)+1
  if ok==64:exact+=1
 return total,correct,exact,conf

def tune(data):
 best=None
 for central in [0.02,0.04,0.06,0.08,0.1,0.12,0.15]:
  for area in [0.02,0.03,0.04,0.05,0.06,0.08]:
   t,c,e,conf=score(data,0,central,area)
   if best is None or c>best[0]: best=(c,central,area,c/t,e,conf)
 return best
if __name__=='__main__':
 mode=sys.argv[1]
 data=extract(mode)
 pickle.dump(data,open(f'.agent-work/tmp/proto4_{mode}.pkl','wb'))
 b=tune(data)
 print(mode,'BEST',b[:5])
 for k,v in sorted(b[5].items(),key=lambda kv:-kv[1])[:20]:print(' ',k,v)
