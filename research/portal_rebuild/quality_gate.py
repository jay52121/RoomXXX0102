#!/usr/bin/env python3
"""Strict causal quality gate. No prediction logic; accepts observed publications only."""
from __future__ import annotations
import argparse, hashlib, json, sys
from collections import deque
from pathlib import Path
from typing import Any

EARLY_MS = -100
LATE_MS = 1000
TARGET_MS = 500

def _integer(x: Any, name: str) -> int:
    if isinstance(x, bool) or not isinstance(x, int):
        raise ValueError(f"{name} must be integer milliseconds")
    return x

def match(truths: list[dict], events: list[dict], late_ms: int = LATE_MS) -> list[tuple[int,int]]:
    """Maximum-cardinality, minimum-time-error bipartite matching; no greedy stealing."""
    nt, ne = len(truths), len(events)
    source, sink = nt + ne, nt + ne + 1
    graph: list[list[list[int]]] = [[] for _ in range(sink + 1)]
    def edge(a: int, b: int, cost: int) -> int:
        i = len(graph[a])
        graph[a].append([b, len(graph[b]), 1, cost])
        graph[b].append([a, i, 0, -cost])
        return i
    for i in range(nt): edge(source, i, 0)
    for j in range(ne): edge(nt+j, sink, 0)
    refs = []
    for i, t in enumerate(truths):
        for j, e in enumerate(events):
            dt = e['responseMs'] - t['timeMs']
            if (e['roomId'], e['direction']) == (t['portalRoomId'], t['direction']) and EARLY_MS <= dt <= late_ms:
                refs.append((i, j, edge(i, nt+j, abs(dt))))
    while True:
        dist = [float('inf')] * len(graph); dist[source] = 0
        prev = [None] * len(graph); q = deque([source]); active = {source}
        while q:
            a = q.popleft(); active.discard(a)
            for k, (b, rev, cap, cost) in enumerate(graph[a]):
                if cap and dist[b] > dist[a]+cost:
                    dist[b] = dist[a]+cost; prev[b] = (a,k)
                    if b not in active: q.append(b); active.add(b)
        if prev[sink] is None: break
        v = sink
        while v != source:
            a,k = prev[v]; b,rev,_,_ = graph[a][k]
            graph[a][k][2] -= 1; graph[b][rev][2] += 1; v = a
    return [(i,j) for i,j,k in refs if graph[i][k][2] == 0]

def publications(data: dict) -> list[dict]:
    """Use containing frame, never top-level outputs or backdated event.timeMs."""
    result = []; last = -1
    for frame_index, f in enumerate(data['frames']):
        now = _integer(f['timeMs'], 'frame.timeMs')
        if now <= last: raise ValueError('Frame time is not strictly increasing')
        last = now
        for e in f.get('outputs', []):
            direction = e['direction']
            if direction not in ('ENTER','EXIT'): raise ValueError('Unknown output direction')
            result.append(dict(responseMs=now, eventMs=_integer(e['timeMs'],'event.timeMs'),
                roomId=e['to'] if direction=='ENTER' else e['from'], direction=direction,
                gateId=e['gateId'], track=e.get('track'), frameIndex=frame_index))
    return result

def score(truths: list[dict], events: list[dict]) -> dict:
    for t in truths:
        _integer(t['timeMs'], 'truth.timeMs')
        if not t.get('portalRoomId') or t['direction'] not in ('ENTER','EXIT'):
            raise ValueError('All truth events require explicitly bound room and direction')
    ids = [t['id'] for t in truths]
    if len(ids)!=len(set(ids)) or not ids: raise ValueError('Truth IDs must be unique and nonempty')
    for e in events:
        _integer(e['responseMs'], 'event.responseMs')
        if not e.get('roomId') or e['direction'] not in ('ENTER','EXIT'):
            raise ValueError('Each event requires room and direction')
    pairs = match(truths,events); used = {j for _,j in pairs}; matched = {i for i,_ in pairs}
    rows = [dict(id=truths[i]['id'], roomId=truths[i]['portalRoomId'], direction=truths[i]['direction'],
        truthMs=truths[i]['timeMs'], responseMs=events[j]['responseMs'],
        deltaMs=events[j]['responseMs']-truths[i]['timeMs'], outputIndex=j) for i,j in pairs]
    full = len(pairs)==len(truths) and len(used)==len(events)
    quick = sum(r['deltaMs'] <= TARGET_MS for r in rows)
    return dict(status='PASS' if full else 'FAIL', truthCount=len(truths), outputs=len(events),
        matched=len(pairs), missed=len(truths)-len(pairs), extras=len(events)-len(used),
        within500=quick, target500Status='PASS' if full and quick==len(truths) else 'FAIL',
        pairs=rows, missingIds=[t['id'] for i,t in enumerate(truths) if i not in matched],
        extraOutputs=[e for j,e in enumerate(events) if j not in used])

def audit_file(path: Path) -> dict:
    raw = path.read_bytes(); data=json.loads(raw)
    events=publications(data); result=score(data['groundTruth'],events)
    truths_json=json.dumps(data['groundTruth'],sort_keys=True,separators=(',',':')).encode()
    ms=[m for f in data['frames'] for m in f['input'].get('measurements',[])]
    signals=[m for f in data['frames'] for m in f['input'].get('signals',[])]
    return dict(file=path.name, sha256=hashlib.sha256(raw).hexdigest(), algorithm=data['algorithm'],
        truthSha256=hashlib.sha256(truths_json).hexdigest(), frames=len(data['frames']),
        measurements=len(ms), signals=len(signals),
        opticalAvailable=sum(m.get('normalFlow') is not None for m in ms),
        backdatedEvents=sum(e['eventMs']!=e['responseMs'] for e in events), quality=result)

def audit_snapshot(path: Path) -> list[dict]:
    snap=json.loads(path.read_text(encoding='utf-8'))
    if snap.get('purpose')!='Immutable recorded outcomes; never inference input':
        raise ValueError('Not an explicitly labeled recorded-output audit snapshot')
    rooms=snap['rooms']
    truths=[dict(id=i+1,timeMs=t,portalRoomId=rooms[k],direction=d)
            for i,(t,k,d) in enumerate(snap['truth'])]
    return [dict(algorithm=r['algorithm'],sha256=r['sourceSha256'],frames=r['frames'],
        quality=score(truths,[dict(responseMs=t,eventMs=e,roomId=rooms[k],direction=d)
               for t,e,k,d in r['events']])) for r in snap['recordings']]

def score_predictions(paths: list[Path], prediction_path: Path) -> list[dict]:
    import re
    supplied=json.loads(prediction_path.read_text(encoding='utf-8'))
    if not re.fullmatch(r'[0-9a-f]{40}',supplied.get('codeSha','')):
        raise ValueError('One pinned Git codeSha is required for the whole candidate')
    runs=supplied.get('runs',[])
    by_sha={r['sourceSha256']:r for r in runs}
    if len(by_sha)!=len(runs):raise ValueError('Duplicate prediction runs')
    reports=[]
    for path in paths:
        raw=path.read_bytes();sha=hashlib.sha256(raw).hexdigest();data=json.loads(raw)
        if sha not in by_sha:raise ValueError('Missing prediction run for '+path.name)
        r=by_sha.pop(sha)
        if r.get('status')!='COMPLETE' or r.get('consumedFrames')!=len(data['frames']):
            raise ValueError('Incomplete or blocked inference cannot pass: '+path.name)
        publications(data)
        frames={_integer(f['frameSeq'], 'frameSeq'):f for f in data['frames']}
        if len(frames)!=len(data['frames']):raise ValueError('Duplicate input frame identifiers')
        gate_room={g['id']:g['room'] for g in data['frames'][0]['input']['context']['gates']}
        es=[];previous_publication=-1
        for e in r['events']:
            _integer(e['responseFrameSeq'],'responseFrameSeq')
            if e['responseFrameSeq'] not in frames:raise ValueError('Invented response frame')
            now=frames[e['responseFrameSeq']]['timeMs']
            if 'responseMs' in e:_integer(e['responseMs'],'responseMs')
            if e.get('responseMs',now)!=now:raise ValueError('Backdated actual response')
            if now<previous_publication:raise ValueError('Publication sequence is not causal')
            previous_publication=now
            if gate_room.get(e['gateId'])!=e['roomId']:raise ValueError('Room/gate contradiction')
            es.append(dict(e,responseMs=now))
        reports.append(dict(file=path.name,sha256=sha,algorithm=supplied['codeSha'],
                            quality=score(data['groundTruth'],es)))
    if by_sha:raise ValueError('Prediction runs lack their original input files')
    return reports

def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recordings', nargs='*',type=Path)
    parser.add_argument('--snapshot',type=Path)
    parser.add_argument('--predictions',type=Path,help='New inference over the original frozen inputs')
    parser.add_argument('--report',required=True,type=Path)
    args=parser.parse_args()
    if bool(args.snapshot)==bool(args.recordings):
        raise ValueError('Use either raw recordings or an explicit --snapshot, not both')
    if args.predictions and args.snapshot:
        raise ValueError('New predictions require original frames, not the outcome-only snapshot')
    reports=(score_predictions(args.recordings,args.predictions) if args.predictions else
             audit_snapshot(args.snapshot) if args.snapshot else [audit_file(p) for p in args.recordings])
    manifest=json.loads(Path(__file__).with_name('recorded_outcomes.json').read_text())
    required={r['sourceSha256'] for r in manifest['recordings']}
    supplied={r['sha256'] for r in reports}
    if len(supplied)!=len(reports) or not required.issubset(supplied):
        raise ValueError('All six frozen baseline inputs are required, without duplicates')
    result=dict(mode='candidate_inference' if args.predictions else 'historical_output_audit',
        contract=dict(earlyMs=EARLY_MS,lateMs=LATE_MS,targetMs=TARGET_MS),
        allPass=all(r['quality']['status']=='PASS' for r in reports),
        allTarget500=all(r['quality']['target500Status']=='PASS' for r in reports), recordings=reports)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    for r in reports:
        q=r['quality']; print(r['algorithm'],q['status'],f"{q['matched']}/{q['truthCount']}",
                            'extras=',q['extras'],'within500=',q['within500'])
    return 0 if result['allPass'] else 1

if __name__=='__main__':
    try: sys.exit(main())
    except (ValueError, KeyError, OSError) as exc:
        print(f'ERROR: {exc}',file=sys.stderr); sys.exit(2)
