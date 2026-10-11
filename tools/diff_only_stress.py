"""差分原型泛化代理测试：重采样、数值扰动、ID 重编码、负场景。

不使用人工事件或输出信息决定事件，真值仅参与每次推断后的计分。
"""
import argparse
import copy
import json
import random
from pathlib import Path

from diff_only_proto import GateSignal, difference_signals, gate_episodes, infer, match_events


def scoring(frames, gt, window):
    events, raw = infer(frames)
    matched, extras = match_events(events, gt, window_ms=window)
    return len(matched), len(extras), len(events)


def transform(frames, rng, kind, rate):
    if kind == 'drop':
        return [copy.deepcopy(f) for f in frames if rng.random() >= rate]
    result = copy.deepcopy(frames)
    if kind == 'jitter':
        for f in result:
            f['timeMs'] += rng.randint(-rate, rate)
    elif kind == 'signal_noise':
        for f in result:
            for p in f['portals']:
                if p.get('bodySide') is not None:
                    p['bodySide'] += rng.uniform(-rate*.15, rate*.15)
                if p.get('bodyInsideRatio') is not None:
                    p['bodyInsideRatio'] = max(0, min(1,p['bodyInsideRatio']+rng.uniform(-rate, rate)))
                if p.get('owned'):
                    o=p['owned']
                    o['pixels']=max(0,int(o['pixels']*(1+rng.uniform(-rate,rate))))
                    if o.get('p50') is not None:
                        o['p50']=max(0,min(1,o['p50']+rng.uniform(-rate,rate)))
    elif kind == 'track_relabel':
        for f in result:
            for p in f['portals']:
                if p['ownerTrack'] is not None:
                    p['ownerTrack'] = 10000 + p['ownerTrack']*17
    elif kind == 'strip_nonvisual':
        for f in result:
            for k in ('outputs','people','notes','counts'):
                f.pop(k, None)
            if f.get('input'):
                f['input'] = {'context': f['input']['context']}
    return result


def synthetic_static_occlusion(room):
    """负样例：一个人站在门里不穿过，随后在 ROI 之外被遮挡。
    系统应拒绝：即使差分前景持续明显，也缺乏由客厅向室内迁移的证据。
    """
    frames=[]
    for i in range(18):
        t=i*110
        active=i<10
        frames.append({'timeMs':t,'portals':[{'gateId':room+'#synthetic','ownerTrack':44,
            'phase':'ACTIVE' if active else 'HOLD',
            'owned':{'pixels':1200 if active else 0,'p50':.4 if active else None},
            'motion':{'pixels':60 if active else 0},
            'bodySide':-.12 if active else None,
            'bodyAlong':.5 if active else None,
            'bodyInsideRatio':.97 if active else None}]})
    return frames


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('path',type=Path)
    ap.add_argument('--rounds',type=int,default=30)
    args=ap.parse_args()
    d=json.loads(args.path.read_text(encoding='utf-8'))
    frames=d['frames'];gt=d['groundTruth'];window=d['matchWindowMs']
    print('ORIGINAL',scoring(frames,gt,window))
    for kind,rate in [('strip_nonvisual',0),('track_relabel',0),('jitter',20),
                      ('drop',.03),('drop',.10),('drop',.20),
                      ('signal_noise',.02),('signal_noise',.05)]:
        scores=[]
        for n in range(args.rounds if kind not in ('track_relabel','strip_nonvisual') else 1):
            transformed=transform(frames,random.Random(n+20261012),kind,rate)
            scores.append(scoring(transformed,gt,window))
        perfect=sum(x==(len(gt),0,len(gt)) for x in scores)
        print(f'{kind:17} {str(rate):5} cases={len(scores)} perfect={perfect} '
              f'min_hits={min(x[0] for x in scores)} avg_hits={sum(x[0] for x in scores)/len(scores):.2f} '
              f'max_fp={max(x[1] for x in scores)} avg_fp={sum(x[1] for x in scores)/len(scores):.2f}')
    room=gt[0]['portalRoomId']
    false,raw=infer(synthetic_static_occlusion(room))
    print('NEGATIVE_STATIONARY_OCCLUSION',len(false),'classification',len(raw))


if __name__=='__main__':
    main()