import csv,json,os,sys
import cv2,numpy as np,chess
TYPES=['pawn','knight','bishop','rook','queen','king']
BOARD=768; CELL=96
COL={'white':0,'black':1}

def warp(img,quad):
 src=np.float32(quad); dst=np.float32([[0,0],[BOARD,0],[BOARD,BOARD],[0,BOARD]])
 return cv2.warpPerspective(img,cv2.getPerspectiveTransform(src,dst),(BOARD,BOARD))

def load_templates(theme):
 ts={}
 for col in ('white','black'):
  for typ in TYPES:
   p=f'dataset/pieces/{theme}/{col}_{typ}.png'
   im=cv2.imread(p,cv2.IMREAD_UNCHANGED)
   if im is None: continue
   ts[(col,typ)]=(im[:,:,:3].astype(np.float32), im[:,:,3].astype(np.float32)/255.0)
 return ts

def backgrounds(cells):
 out={}
 for parity in (0,1):
  st=np.stack([cells[r*8+c] for r in range(8) for c in range(8) if ((r+c)&1)==parity])
  out[parity]=np.median(st,axis=0).astype(np.float32)
 return out

def sim(cell,target,mask=None):
 if mask is None: mask=np.ones(cell.shape[:2],bool)
 if mask.sum()==0: return 0
 return 1-np.abs(cell[mask]-target[mask]).mean()/255.0

def mask_cell(cell,bg):
 diff=np.abs(cell-bg).max(axis=2)
 lum=lambda x: .299*x[...,0]+.587*x[...,1]+.114*x[...,2]
 m=((diff>26)|(np.abs(lum(cell)-lum(bg))>30)).astype(np.uint8)
 m[:3]=0;m[-3:]=0;m[:,:3]=0;m[:,-3:]=0
 m=cv2.morphologyEx(m,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
 m=cv2.morphologyEx(m,cv2.MORPH_CLOSE,np.ones((5,5),np.uint8))
 return m.astype(bool)

def run(rows, empty_margin=0.02, central_min=0.03, area_min=0.02, verbose=False):
 total=correct=exact=0; conf={}; details=[]; cache={}
 for row in rows:
  img=cv2.imread(row['image']); board=warp(img,json.loads(row['quad']))
  cells=[board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32) for r in range(8) for c in range(8)]
  bgs=backgrounds(cells); theme=row['theme']
  ts=cache.setdefault(theme,load_templates(theme))
  bd=chess.Board(row['fen']); exp={}
  for sq in chess.SQUARES:
   pc=bd.piece_at(sq); exp[(7-chess.square_rank(sq),chess.square_file(sq))]=None if pc is None else ('white' if pc.color else 'black',TYPES['pnbrqk'.index(pc.symbol().lower())])
  ok=0
  for r in range(8):
   for c in range(8):
    cell=cells[r*8+c];bg=bgs[(r+c)&1];m=mask_cell(cell,bg)
    empty=sim(cell,bg)
    central=m[24:72,24:72].mean(); area=m.mean()
    best=None
    for key,(rgb,a) in ts.items():
     comp=rgb*a[...,None]+bg*(1-a[...,None])
     score=sim(cell,comp,a>0.2)
     if best is None or score>best[0]: best=(score,key)
    # occupied if piece score exceeds bg and central evidence
    if central<central_min or area<area_min or (best is None) or empty>=best[0]-empty_margin:
     pred=None
    else: pred=best[1]
    e=exp[(r,c)]
    total+=1
    if e==pred: ok+=1;correct+=1
    else:
     conf[(e,pred)]=conf.get((e,pred),0)+1
     if verbose and len(details)<30: details.append((row['id'],r,c,e,pred,round(empty,3),round(best[0],3) if best else None,round(central,3),round(area,3)))
  if ok==64:exact+=1
 return total,correct,exact,conf,details

if __name__=='__main__':
 rows=list(csv.DictReader(open('dataset/labels.csv')))
 for margin in [0.0,0.01,0.02,0.03,0.05]:
  total,correct,exact,conf,details=run(rows,empty_margin=margin,verbose=False)
  print('margin',margin,'piece',correct/total,'exact',exact,'/',len(rows))
 total,correct,exact,conf,details=run(rows,empty_margin=0.02,verbose=True)
 for k,v in sorted(conf.items(),key=lambda kv:-kv[1])[:20]: print(' ',k,v)
 for d in details[:30]:print(' ',d)
