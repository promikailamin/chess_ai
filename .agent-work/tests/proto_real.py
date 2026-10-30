import json,cv2,numpy as np,chess,sys
from proto4 import warp,load_templates,cell_bg,sim,mask_cell,CELL,TYPES,BOARD

def analyze(imgpath,quad,theme,fen):
 img=cv2.imread(imgpath);board=warp(img,quad)
 cells=[board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32) for r in range(8) for c in range(8)]
 ts=load_templates(theme);bd=chess.Board(fen);exp={}
 for sq in chess.SQUARES:
  pc=bd.piece_at(sq);exp[(7-chess.square_rank(sq),chess.square_file(sq))]=None if pc is None else ('white' if pc.color else 'black',TYPES['pnbrqk'.index(pc.symbol().lower())])
 ok=0;lines=[]
 for r in range(8):
  row=[]
  for c in range(8):
   cell=cells[r*8+c];bg=cell_bg(cell,mode='corners');m=mask_cell(cell,bg)
   central=m[24:72,24:72].mean();area=m.mean();best=None
   for key,(rgb,a) in ts.items():
    comp=rgb*a[...,None]+bg*(1-a[...,None]);score=sim(cell,comp,a>0.2)
    if best is None or score>best[0]:best=(score,key)
   pred=None if central<0.02 or area<0.02 else best[1]
   e=exp[(r,c)];ok+= e==pred
   row.append((e,pred,round(float(central),2),round(float(area),2),round(float(best[0]),2)))
  lines.append(row)
 return ok,lines

if __name__=='__main__':
 m=json.load(open('dataset/reference_manifest.json'))
 total=0
 for r in m['references']:
  ok,lines=analyze(r['file'],r['quad'],r['theme'],r['fen']);total+=ok
  print(r['id'],r['theme'],ok,'/64')
  if ok<64:
   for row in lines:
    for e,p,central,area,score in row:
     if e!=p: print('  err',e,p,central,area,score)
 print('total',total,'/',64*len(m['references']))
 # issue
 quad=[[753,42],[1756,42],[1763,1043],[746,1043]]
 fen='rnbqkbnr/pppp1ppp/8/4p3/8/2N5/PPPPPPPP/R1BQKBNR w KQkq - 0 2'
 ok,lines=analyze('.agent-work/tmp/issue11/issue.png',quad,'walnut-flat',fen)
 print('ISSUE',ok,'/64')
 for row in lines:
  for e,p,central,area,score in row:
   if e!=p: print('  err',e,p,central,area,score)
