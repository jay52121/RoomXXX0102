#!/usr/bin/env python3
"""Reproduce the supervised calibration. Labels are training targets, never features.
Runtime uses only 69 causal upper-body/geometry features; there are no timestamps,
frame indexes, room IDs, annotation IDs or route positions in the feature vector.
Requires catboost/numpy for training only, not on Android.
"""
from __future__ import annotations
import argparse,gzip,hashlib,json,struct,subprocess
from pathlib import Path
import numpy as np
from catboost import CatBoostClassifier
ROOT=Path(__file__).resolve().parents[2]

def export(model,path):
    tmp=path.with_suffix('.catboost.json');model.save_model(str(tmp),format='json');j=json.loads(tmp.read_text(encoding='utf-8'))
    scale,bias=j['scale_and_bias'];buf=bytearray(b'SISPTM01')
    buf+=struct.pack('>iii',69,len(j['oblivious_trees']),3)+struct.pack('>dddd',scale,*bias)
    for t in j['oblivious_trees']:
        buf+=struct.pack('>i',len(t['splits']))
        for s in t['splits']:
            if s['split_type']!='FloatFeature':raise ValueError('Unsupported categorical split')
            buf+=struct.pack('>if',s['float_feature_index'],s['border'])
        buf+=struct.pack('>'+str(len(t['leaf_values']))+'d',*t['leaf_values'])
    path.write_bytes(gzip.compress(bytes(buf),mtime=0))

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True)
    group=p.add_mutually_exclusive_group();group.add_argument('--leave-recording',type=int,choices=range(6));group.add_argument('--exclude-action-group',type=int,choices=range(3));args=p.parse_args()
    work=args.output.resolve().parent;work.mkdir(parents=True,exist_ok=True)
    jar=work/'extract.jar';src=ROOT/'app/src/main/java/com/example/roomxxx0102/logic/roomalgorithm/temporal/TemporalPortalCore.kt'
    subprocess.run(['kotlinc',str(src),str(ROOT/'tools/portal_temporal/NativeReplayMain.kt'),'-include-runtime','-d',str(jar)],check=True)
    manifest=json.loads((ROOT/'tools/portal_temporal/recordings_manifest.json').read_text(encoding='utf-8'))
    xs=[];ys=[]
    for rec in manifest:
        if rec['id']==args.leave_recording:continue
        fixture=ROOT/f"tools/portal_temporal/fixtures/recording_{rec['id']}.replay.gz"
        if hashlib.sha256(fixture.read_bytes()).hexdigest()!=rec['fixture_sha256']:raise ValueError('Fixture hash mismatch')
        truth=sorted([{k:g[k] for k in ('id','timeMs','direction','portalRoomId')} for g in rec['truth']],key=lambda x:x['id'])
        frozen=hashlib.sha256(json.dumps(truth,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
        if frozen!='c5ccf03445ab2f11b4043a15c712509d8cdcc5863871df40bd4e5ec151987988':raise ValueError('Original annotations changed')
        f=work/f"features_{rec['id']}.bin"
        # The packaged model is used only to exercise the runtime. It has no
        # influence on the observation encoder; its emitted events are ignored.
        subprocess.run(['java','-Xmx128m','-jar',str(jar),str(ROOT/'app/src/main/assets/portal_temporal/scene_model.bin.gz'),str(ROOT/f"tools/portal_temporal/fixtures/recording_{rec['id']}.replay.gz"),str(work/'ignored_predictions.json'),'0','1','0','0',str(f)],check=True)
        b=f.read_bytes();pos=4
        if struct.unpack('>i',b[:4])[0]!=69:raise ValueError('Encoder dimension mismatch')
        while pos<len(b):
            time,n=struct.unpack_from('>qH',b,pos);pos+=10;gate=b[pos:pos+n].decode();pos+=n+4
            x=struct.unpack_from('>69f',b,pos);pos+=276
            if args.exclude_action_group is not None:
                lo,hi=[(0,54000),(54000,104000),(104000,10**12)][args.exclude_action_group]
                if lo<=time<hi:continue
            label=0
            for gt in rec['truth']:
                if gate.split('#')[0]==gt['portalRoomId'] and 80<=time-gt['timeMs']<=650:
                    label=1 if gt['direction']=='ENTER' else 2;break
            xs.append(x);ys.append(label)
    x=np.asarray(xs,np.float32);y=np.asarray(ys,np.int32)
    model=CatBoostClassifier(iterations=350,depth=5,learning_rate=.08,loss_function='MultiClass',random_seed=43,thread_count=4,l2_leaf_reg=12,allow_writing_files=False,verbose=100)
    model.fit(x,y,sample_weight=np.where(y==0,1.,4.));export(model,args.output.resolve())
    print('Calibration complete. Run the unchanged strict evaluator and held-out-action tests; training fit is not generalization.')
if __name__=='__main__':main()
