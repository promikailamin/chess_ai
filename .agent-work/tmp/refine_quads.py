import cv2, numpy as np, glob, json, re
from scipy.signal import find_peaks

SIZE=768

def warp(img,q,size=SIZE):
    src=np.float32(q); dst=np.float32([[0,0],[size,0],[size,size],[0,size]])
    h=cv2.getPerspectiveTransform(src,dst)
    return cv2.warpPerspective(img,h,(size,size))

def profile(gray, axis, lo, hi):
    # gradient across axis; axis='v' -> vertical lines, use gx, sum over selected rows
    gx=cv2.Sobel(gray,cv2.CV_32F,1,0,ksize=3)
    gy=cv2.Sobel(gray,cv2.CV_32F,0,1,ksize=3)
    mag=cv2.magnitude(gx,gy)
    if axis=='v':
        p=np.abs(gx)[lo:hi,:].sum(axis=0)
    else:
        p=np.abs(gy)[:,lo:hi].sum(axis=1)
    p=np.convolve(p,np.ones(3)/3,mode='same')
    return p

def detect_lines(p, expected_step=96):
    n=len(p)
    # coarse search offset and spacing
    best=None
    for off in np.arange(-45,45.1,1.0):
        for step in np.arange(expected_step*0.90, expected_step*1.10, 0.5):
            pos=off+np.arange(9)*step
            if pos[0]<-10 or pos[-1]>n+10: continue
            s=0
            for x in pos:
                xi=int(round(x)); a=max(0,min(n-1,xi-2)); b=max(a+1,min(n,xi+3))
                s+=p[a:b].max()
            if best is None or s>best[0]: best=(s,off,step)
    s,off,step=best
    pos=[]
    for k in range(9):
        x=off+k*step; xi=int(round(x)); a=max(0,min(n-1,xi-4)); b=max(a+1,min(n,xi+5))
        j=a+int(np.argmax(p[a:b])); pos.append(float(j))
    return np.array(pos)

def fit_projective(src,dst):
    # f(x)=(a*x+b)/(c*x+1), least squares via linear system
    A=[]; y=[]
    for x,d in zip(src,dst):
        A.append([x,1,-d*x]); y.append(d)
    sol,*_=np.linalg.lstsq(np.array(A),np.array(y),rcond=None)
    a,b,c=sol
    return lambda x: (a*x+b)/(c*x+1)

def refine(img,q, verbose=False):
    b=warp(img,q)
    g=cv2.cvtColor(b,cv2.COLOR_BGR2GRAY).astype(np.float32)
    # use middle rows/cols where board is empty for initial positions
    lo=int(SIZE*0.28); hi=int(SIZE*0.72)
    pv=profile(g,'v',lo,hi); ph=profile(g,'h',lo,hi)
    x=detect_lines(pv); y=detect_lines(ph)
    if verbose:
        print('lines x',np.round(x,1),'y',np.round(y,1))
    target=np.arange(9)*SIZE/8.0
    fx=fit_projective(x,target); fy=fit_projective(y,target)
    # invert numerically
    def inv(f,t):
        # solve f(x)=t by bisection on [0,SIZE]
        lo,hi=0.0,float(SIZE)
        for _ in range(60):
            mid=(lo+hi)/2
            if f(mid)<t: lo=mid
            else: hi=mid
        return (lo+hi)/2
    src=np.float32(q)
    dst=np.float32([[0,0],[SIZE,0],[SIZE,SIZE],[0,SIZE]])
    H=cv2.getPerspectiveTransform(src,dst)
    Hinv=np.linalg.inv(H)
    def image_from_warp(xw,yw):
        v=Hinv@np.array([xw,yw,1.0]); v/=v[2]; return [v[0],v[1]]
    # new canonical corners -> old warped coords -> image
    corners=[]
    for u,v in [(0,0),(1,0),(1,1),(0,1)]:
        xw=inv(fx,u*SIZE); yw=inv(fy,v*SIZE)
        corners.append(image_from_warp(xw,yw))
    return np.array(corners,dtype=np.float64)

if __name__=='__main__':
    import sys
    # baseline quads
    log=open('.agent-work/logs/baseline-analysis.log').read(); baseline={}; cur=None
    for line in log.splitlines():
        if line.startswith('===== '): cur=line[6:].strip()
        elif line.startswith('Board       :'):
            pairs=re.findall(r'\((-?\d+),(-?\d+)\)',line)
            if len(pairs)==4: baseline[cur]=[[int(x),int(y)] for x,y in pairs]
    out={}
    for name,q in baseline.items():
        key=name.replace('Screenshot_2026-10-29-','').replace('_com.llx.chess.jpg','')
        img=cv2.imread('screenshots/'+name)
        try:
            nq=refine(img,np.array(q,float),verbose=True)
            out[key]=np.round(nq).astype(int).tolist()
            print(key,out[key])
        except Exception as e:
            print(key,'ERR',e)
    open('.agent-work/tmp/refined_quads2.json','w').write(json.dumps(out,indent=2))
