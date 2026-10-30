import csv,json,sys,pickle
import cv2,numpy as np,chess
sys.path.insert(0,'.agent-work/tests')
from proto4 import warp,cell_bg,cell_bg,CELL,BOARD,TYPES,mask_cell
NORM=48

def norm(mask):
 ys,xs=np.nonzero(mask)
 out=np.zeros((NORM,NORM),bool)
 if len(xs)==0:return out
 x0,x1,y0,y1=xs.min(),xs.max(),ys.min(),ys.max()
 crop=mask[y0:y1+1,x0:x1+1];h,w=crop.shape;sc=(NORM-1.0)/max(w,h)
 nw=max(1,int(round(w*sc)));nh=max(1,int(round(h*sc)))
 r=cv2.resize(crop.astype(np.uint8),(nw,nh),interpolation=cv2.INTER_NEAREST)
 ox=(NORM-nw)//2;oy=(NORM-nh)//2;out[oy:oy+nh,ox:ox+nw]=r>0
 return out

def dice(a,b):
 return 2*np.logical_and(a,b).sum()/max(1,a.sum()+b.sum())

def load(theme):
 ts={}
 for col in ('white','black'):
  for typ in TYPES:
   im=cv2.imread(f'dataset/pieces/{theme}/{col}_{typ}.png',cv2.IMREAD_UNCHANGED)
   if im is None:continue
   rgb=im[:,:,2::-1].astype(np.float32);a=im[:,:,3]>96
   mean=rgb[a].mean(axis=0)
   ts[(col,typ)]=(norm(a),mean,a)
 return ts

def run(rows):
 cache={};total=correct=exact=0;conf={}
 for row in rows:
  img=cv2.imread(row['image']);board=warp(img,json.loads(row['quad']))
  cells=[board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32) for r in range(8) for c in range(8)]
  theme=row['theme'];ts=cache.setdefault(theme,load(theme))
  bd=chess.Board(row['fen']);exp={}
  for sq in chess.SQUARES:
   pc=bd.piece_at(sq);exp[(7-chess.square_rank(sq),chess.square_file(sq))]=None if pc is None else ('white' if pc.color else 'black',TYPES['pnbrqk'.index(pc.symbol().lower())])
  ok=0
  for r in range(8):
   for c in range(8):
    cell=cells[r*8+c];bg=cell_bg(cell,mode='corners');m=mask_cell(cell,bg)
    central=m[24:72,24:72].mean();area=m.mean()
    if central<0.02 or area<0.02:pred=None
    else:
     ns=norm(m); pixelmean=cell[m].mean(axis=0)
     best=None
     for key,(nt,mean,a) in ts.items():
      shape=dice(ns,nt)
      col=1-np.abs(pixelmean-mean).mean()/255
      score=0.75*shape+0.25*col
      if best is None or score>best[0]:best=(score,key,shape,col)
     pred=best[1]
    e=exp[(r,c)];total+=1
    if e==pred:ok+=1;correct+=1
    else:conf[(e,pred)]=conf.get((e,pred),0)+1
  if ok==64:exact+=1
 return total,correct,exact,conf

if __name__=='__main__':
 rows=list(csv.DictReader(open('dataset/labels.csv')))
 t,c,e,conf=run(rows);print('shape',c/t,e,'/',len(rows))
 for k,v in sorted(conf.items(),key=lambda kv:-kv[1])[:20]:print(' ',k,v)
