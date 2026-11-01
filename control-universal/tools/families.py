import sys, collections, json
sys.path.insert(0, sys.argv[1])
from parse import load_all
ROOT=sys.argv[2] if len(sys.argv)>2 else None
ALIASES={
 'power':['power','on_off','power_toggle'],
 'vol_up':['vol_up','vol+','volume_up'],'vol_dn':['vol_dn','vol_down','vol-','volume_down'],
 'mute':['mute'],'ch_up':['ch_next','ch_up','ch+'],'ch_dn':['ch_prev','ch_down','ch-'],
 'up':['up','arrow_up','up_arrow'],'down':['down','arrow_down','down_arrow','dwn','dn'],
 'left':['left','arrow_left','left_arrow'],'right':['right','arrow_right','right_arrow'],
 'ok':['ok','enter','select','ent'],'back':['back','return','exit'],
 'home':['home','smart_hub','apps'],'menu':['menu','settings','setup'],
 'source':['source','input','sources'],
}
for d in '0123456789': ALIASES[d]=[d]
REV={}
for k,vs in ALIASES.items():
    for i,v in enumerate(vs): REV[v]=(k,i)
def families(sigs):
    fam=collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
    files=collections.defaultdict(set)
    for s in sigs:
        if s.get('type')!='parsed': continue
        n=s['name'].lower()
        if n not in REV: continue
        k,pri=REV[n]
        key=(s['protocol'],s['address'])
        fam[key][k][(pri,s['command'])]+=1
        files[key].add(s['file'])
    return fam,files
if __name__=='__main__':
    allb=load_all(ROOT)
    allb={k:v for k,v in allb.items()}
    out={}
    for brand in sys.argv[3:]:
        fam,files=families(allb[brand])
        ranked=sorted(fam.items(), key=lambda kv:(-len(files[kv[0]]),-len(kv[1])))
        print('=====',brand)
        for key,keys in ranked[:6]:
            print(' ',key,'files',len(files[key]),'keys',len(keys),'power',dict(keys.get('power',{})))
