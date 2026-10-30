import csv,json,sys,glob,os
import cv2,numpy as np,chess
sys.path.insert(0,'.agent-work/tests')
from proto4 import warp,cell_bg,CELL,TYPES,mask_cell

def load_theme(theme):
 bgs=[];fgs=[]
 for col in ('white','black'):
  for typ in TYPES:
   im=cv2.imread(f'dataset/pieces/{theme}/{col}_{typ}.png',cv2.IMREAD_UNCHANGED)
   if im is None:continue
   rgb=im[:,:,2::-1].astype(np.float32);a=im[:,:,3]
   # bg from corners
   k=14;pix=np.concatenate([rgb[:k,:k].reshape(-1,3),rgb[:k,-k:].reshape(-1,3),rgb[-k:,:k].reshape(-1,3),rgb[-k:,-k:].reshape(-1,3)])
   bgs.append(np.median(pix,axis=0))
   fgs.append(rgb[a>160].mean(axis=0))
 return np.array(bgs),np.array(fgs)

def board_desc(path,quad):
 img=cv2.imread(path);board=warp(img,quad)
 bgs=[];fgs=[]
 for r in range(8):
  for c in range(8):
   cell=board[r*CELL:(r+1)*CELL,c*CELL:(c+1)*CELL].astype(np.float32)
   bg=cell_bg(cell,mode='ring');bgs.append(np.median(bg.reshape(-1,3),axis=0))
   m=mask_cell(cell,bg)
   core=m[34:62,34:62].mean();area=m.mean()
   if area>=0.02 and core>=0.02:
    fgs.append(cell[m].mean(axis=0))
 return np.array(bgs),np.array(fgs)

def dist(a,b):return np.linalg.norm(a-b)
def theme_scores(bgs,fgs,themes):
 # match board bg colors to theme bg set by nearest for each board bg (2 clusters)
 out={}
 # cluster board bgs into two by luminance
 lum=bgs.mean(axis=1);order=np.argsort(lum);bgl=bgs[order[:32]].mean(axis=0);bgd=bgs[order[-32:]].mean(axis=0)
 fg=bgs if len(fgs)==0 else np.concatenate([fgs,bgs])
 # cluster fg by luminance into white/black piece colors
 if len(fgs):
  l=fgs.mean(axis=1);o=np.argsort(l);fw=fgs[o[-max(1,len(o)//4):]].mean(axis=0);fb=fgs[o[:max(1,len(o)//4)]].mean(axis=0)
 else:fw=fb=np.array([128,128,128])
 for t,(tb,tf) in themes.items():
  # for each board bg, nearest theme bg distance
  dbg=np.mean([min(np.linalg.norm(a-b) for b in tb) for a in (bgl,bgd)])
  # white/black fg nearest theme fg
  def near(a):return min(np.linalg.norm(a-b) for b in tf)
  dfg=(near(fw)+near(fb))/2
  out[t]=dbg*0.45+dfg*0.55
 return out

if __name__=='__main__':
 themes={os.path.basename(d):load_theme(os.path.basename(d)) for d in glob.glob('dataset/pieces/*') if os.path.isdir(d)}
 cases=[]
 for row in list(csv.DictReader(open('dataset/labels.csv')))[:60]:cases.append((row['image'],json.loads(row['quad']),row['theme'],row['id']))
 m=json.load(open('dataset/reference_manifest.json'))
 for r in m['references']:cases.append((r['file'],r['quad'],r['theme'],r['id']))
 cases.append(('.agent-work/tmp/issue11/issue.png',[[753,42],[1756,42],[1763,1043],[746,1043]],'walnut-flat','ISSUE'))
 correct=0
 for path,quad,theme,cid in cases:
  bgs,fgs=board_desc(path,quad);scores=theme_scores(bgs,fgs,themes)
  pred=min(scores,key=scores.get);correct+=pred==theme
  if pred!=theme:print('MISS',cid,'exp',theme,'got',pred,{k:round(v,1) for k,v in sorted(scores.items(),key=lambda kv:kv[1])[:3]})
 print('theme accuracy',correct,'/',len(cases))
