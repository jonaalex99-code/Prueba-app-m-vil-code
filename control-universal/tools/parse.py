import os, re, sys, collections, json
ROOT=None
def parse(path):
    out=[];cur=None
    for line in open(path,errors='ignore'):
        line=line.strip()
        if line.startswith('#'):
            if cur: out.append(cur); cur=None
            continue
        if ':' not in line: continue
        k,v=line.split(':',1);k=k.strip();v=v.strip()
        if k=='name':
            if cur: out.append(cur)
            cur={'name':v}
        elif cur is not None: cur[k]=v
    if cur: out.append(cur)
    return out
def load_all(ROOT=None):
    ROOT=ROOT or sys.argv[1]
    res={}
    for brand in sorted(os.listdir(ROOT)):
        d=os.path.join(ROOT,brand)
        if not os.path.isdir(d): continue
        sigs=[]
        for dp,_,fs in os.walk(d):
            for f in fs:
                if f.endswith('.ir'):
                    for s in parse(os.path.join(dp,f)):
                        s['file']=os.path.relpath(os.path.join(dp,f),ROOT); sigs.append(s)
        res[brand]=sigs
    return res
if __name__=='__main__':
    allb=load_all()
    names=collections.Counter()
    for b,s in allb.items():
        for x in s: names[x['name'].lower()]+=1
    print(names.most_common(250))
