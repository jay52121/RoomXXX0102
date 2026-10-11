"""差分独立原型的无录像单元测试；正式录像可选回归测试。"""

import json
import os
from pathlib import Path

from diff_only_proto import (GateSignal, classify,
                             infer, match_events, split_reverse)
from diff_only_stress import synthetic_static_occlusion


ROOM = 'generic_room'


def make_episode(direction: str, *, count: int = 12):
    samples = []
    for i in range(count):
        progress = i/(count-1)
        side = (-.09-.06*progress if direction == 'ENTER' else -.17+.075*progress)
        ratio = (.70+.29*progress if direction == 'ENTER' else .99-.5*progress)
        depth = (.18+.23*progress if direction == 'ENTER' else .55-.27*progress)
        samples.append(GateSignal(i*120, ROOM, 999, 'ACTIVE', 2000,
                                  depth, side, .45, ratio, 500))
    return samples


def test_independent_depth_and_absorption_enter():
    c = classify(make_episode('ENTER'))
    assert c is not None and c.direction == 'ENTER'


def test_reverse_without_feet_or_ground():
    c = classify(make_episode('EXIT'))
    assert c is not None and c.direction == 'EXIT'


def test_no_activity_no_crossing():
    assert infer([{'timeMs':i*100,'portals':[]} for i in range(20)])[0] == []


def test_static_entrance_does_not_count_as_entry():
    assert infer(synthetic_static_occlusion(ROOM))[0] == []


def test_reverse_episode_splitting_with_strong_turn():
    entering = make_episode('ENTER',count=20)
    exiting = make_episode('EXIT',count=20)
    shifted = [GateSignal(x.time_ms+entering[-1].time_ms+120, x.room,x.owner,x.phase,
                          x.diff_pixels,x.depth,x.body_side,x.body_along,max(.75,x.absorption),x.motion_pixels)
               for x in exiting]
    parts = split_reverse(entering+shifted)
    assert len(parts) == 2
    assert classify(parts[0]).direction == 'ENTER'
    assert classify(parts[1]).direction == 'EXIT'


def test_matching_enforces_direction_and_one_to_one():
    events = [classify(make_episode('ENTER'))]
    assert events[0] is not None
    gt = [{'id':1,'timeMs':events[0].time_ms,'portalRoomId':ROOM,'direction':'ENTER'},
          {'id':2,'timeMs':events[0].time_ms,'portalRoomId':ROOM,'direction':'ENTER'},
          {'id':3,'timeMs':events[0].time_ms,'portalRoomId':ROOM,'direction':'EXIT'}]
    m, unused=match_events(events,gt)
    assert len(m)==1 and not unused


def test_jitter_preserves_signals_without_ground_or_algorithm_outputs():
    frames = synthetic_static_occlusion(ROOM)
    for f in frames:
        f['outputs']=[{'fake':'should not be read'}]
        f['people']=[{'groundX':.1,'groundY':.2,'room':'wrong'}]
    a=infer(frames)[0]
    stripped = [{k:v for k,v in f.items() if k not in ('outputs','people')} for f in frames]
    b=infer(stripped)[0]
    assert a==b==[]


def test_original_recording_when_present():
    # Fixture intentionally excluded from GitHub (5.9MB, supplied separately).
    import glob
    import pytest
    files = ([os.environ['SISP_DIFF_REPLAY']] if os.environ.get('SISP_DIFF_REPLAY')
             else glob.glob('/mnt/data/sisp/SISP_DIFFERENCE_*.txt'))
    if not files:
        pytest.skip('full decision-layer recording not installed')
    d=json.loads(Path(files[0]).read_text(encoding='utf-8'))
    events,raw=infer(d['frames'])
    matches,extras=match_events(events,d['groundTruth'],window_ms=d['matchWindowMs'])
    assert len(matches)==32 and len(events)==32 and not extras