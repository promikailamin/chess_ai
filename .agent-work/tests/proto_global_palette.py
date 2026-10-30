import csv,json,glob,os,sys
import cv2,numpy as np,chess
sys.path.insert(0,'.agent-work/tests')
from proto4 import warp,cell_bg,CELL,TYPES,mask_cell
from proto_global_align import load_all,classify,stats
from proto_theme import load_theme,theme_scores
THEMES=[os.path.basename(d) for d in sorted(glob.glob('dataset/pieces/*')) if os.path.isdir(d)]

def run(cases,ts,topk=5,search=0):
 palettes={t:load_theme(t) for t in THEMES}
 total=correct=exact=0;conf={};texp={};cfail=[];topkmiss=0
 for path,quad,fen,theme,cid in cases:
  img=cv2.imread(path);board=warp(img,quad);bd=chess.Board(fen)
  # extract cells/stats once
  cells=[];bgs=[];ms=[];occs=[]
  for r in range(8):
   for c in range(8):
    cell=board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32);bg,m,occ=stats(cell)
    cells.append(cell);bgs.append(bg);ms.append(m);occs.append(occ)
  # palette desc from board
  pal_bgs=[];pal_fgs=[]
  for i in range(64):
   pal_bgs.append(np.median(bgs[i].reshape(-1,3),axis=0))
   if occs[i]:pal_fgs.append(cells[i][ms[i]].mean(axis=0))
  scores=theme_scores(np.array(pal_bgs),np.array(pal_fgs),palettes)
  ranked=[t for t,_ in sorted(scores.items(),key=lambda kv:kv[1])]
  if theme not in ranked[:topk]:topkmiss+=1
  chosen=set(ranked[:topk])
  sub=[t for t in ts if t[0] in chosen]
  ok=0
  for i in range(64):
   r,c=divmod(i,8);e=bd.piece_at((7-r)*8+c);exp=None if e is None else ('white' if e.color else 'black',TYPES['pnbrqk'.index(e.symbol().lower())])
   if not occs[i]:pred=None
   else:
    b=classify(cells[i],sub,bgs[i],ms[i],search);pred=b[2] if b else None
    if b and b[1]!=theme:texp[(theme,b[1])]=texp.get((theme,b[1]),0)+1
   total+=1
   if exp==pred:ok+=1;correct+=1
   else:conf[(exp,pred)]=conf.get((exp,pred),0)+1
  if ok==64:exact+=1
  else:cfail.append((cid,theme,ok))
 return total,correct,exact,conf,texp,cfail,topkmiss

if __name__=='__main__':
 size=int(sys.argv[1]) if len(sys.argv)>1 else 48;topk=int(sys.argv[2]) if len(sys.argv)>2 else 5
 ts=load_all(size)
 rows=list(csv.DictReader(open('dataset/labels.csv')))
 cases=[(r['image'],json.loads(r['quad']),r['fen'],r['theme'],r['id']) for r in rows]
 m=json.load(open('dataset/reference_manifest.json'))
 cases+=[(r['file'],r['quad'],r['fen'],r['theme'],r['id']) for r in m['references']]
 cases.append(('.agent-work/tmp/issue11/issue.png',[[753,42],[1756,42],[1763,1043],[746,1043]],'rnbqkbnr/pppp1ppp/8/4p3/8/2N5/PPPPPPPP/R1BQKBNR w KQkq - 0 2','walnut-flat','ISSUE'))
 t,c,e,conf,texp,cf,miss=run(cases,ts,topk)
 print('palette topk',topk,'size',size,'piece',c/t,'exact',e,'/',len(cases),'topkmiss',miss)
 print('theme confusions',sorted(texp.items(),key=lambda kv:-kv[1])[:10])
 for k,v in sorted(conf.items(),key=lambda kv:-kv[1])[:20]:print(' ',k,v)
 for x in cf[:20]:print(' fail',x)
