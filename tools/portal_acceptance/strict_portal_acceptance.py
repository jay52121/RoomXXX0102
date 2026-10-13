#!/usr/bin/env python3
"""Audit recorded portal outputs. This program is NOT an event detector.

Contract: every marked normal crossing must have exactly one output for the
same door and direction at video T-100..T+1000 ms. T+500 ms is reported as a
separate preferred target. Scores use the frame in which an output appears,
never a retrospective event timestamp. No reduced pass threshold is offered.

Exit 0: every provided recording meets the selected event contract.
Exit 1: at least one fails the event contract.
Exit 2: malformed/incomplete input or modified frozen annotations.
Success never attests to unrecorded wall-clock latency or generalization.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

EARLY_MS = 100
LATEST_MS = 1000
TARGET_MS = 500
EXPECTED_LABELS = 32
# Immutable, normalized 32-label snapshot shared by all six supplied recordings.
EXPECTED_TRUTH_SHA256 = "c5ccf03445ab2f11b4043a15c712509d8cdcc5863871df40bd4e5ec151987988"


@dataclass(frozen=True)
class Event:
    output_index: int
    emitted_ms: int
    declared_ms: int
    room: str
    gate: str
    direction: str
    frame_index: int


def integer(v: Any, name: str) -> int:
    if isinstance(v, bool) or not isinstance(v, int):
        raise ValueError(f'{name}: expected integer, received {v!r}')
    return v


def digest(value: Any) -> str:
    b = json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
    return hashlib.sha256(b).hexdigest()


def match(truth: list[dict[str, Any]], events: list[Event], late_ms: int) -> list[dict[str, Any]]:
    """Maximum-cardinality matching; deterministic, not minimum-total-latency.

    Wrong-door/direction events never consume an otherwise valid pair.
    """
    adj: list[list[int]] = []
    for g in truth:
        adj.append(sorted((e.output_index for e in events if
            e.room == g['portalRoomId'] and e.direction == g['direction'] and
            -EARLY_MS <= e.emitted_ms - g['timeMs'] <= late_ms),
            key=lambda i: (abs(events[i].emitted_ms - g['timeMs']), i)))
    assigned: dict[int, int] = {}

    def augment(i: int, seen: set[int]) -> bool:
        for out_idx in adj[i]:
            if out_idx in seen:
                continue
            seen.add(out_idx)
            if out_idx not in assigned or augment(assigned[out_idx], seen):
                assigned[out_idx] = i
                return True
        return False

    for i in range(len(truth)):
        augment(i, set())
    return sorted((dict(truth_id=truth[i]['id'], output_index=j,
                        latency_ms=events[j].emitted_ms - truth[i]['timeMs'])
                   for j, i in assigned.items()), key=lambda p: p['truth_id'])


def audit(data: dict[str, Any], *, expected_labels: int = EXPECTED_LABELS,
          expected_truth_sha256: str | None = None) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise ValueError('Recording must be a JSON object')
    frames, truth = data.get('frames'), data.get('groundTruth')
    if not isinstance(frames, list) or not frames or not isinstance(truth, list):
        raise ValueError('Missing full frames or groundTruth')
    if len(truth) != expected_labels:
        raise ValueError(f'Label count {len(truth)} != contract count {expected_labels}')
    ids = set()
    for g in truth:
        if not isinstance(g, dict):
            raise ValueError('Each ground-truth item must be an object')
        i, t = integer(g.get('id'), 'truth id'), integer(g.get('timeMs'), 'truth time')
        if i in ids or t < 0 or g.get('direction') not in ('ENTER', 'EXIT') or not isinstance(g.get('portalRoomId'), str) or not g['portalRoomId']:
            raise ValueError('Invalid, duplicated, or unbound ground truth')
        ids.add(i)
    normalized_truth = sorted(({k: g[k] for k in ('id', 'timeMs', 'direction', 'portalRoomId')}
                               for g in truth), key=lambda g: g['id'])
    truth_hash = digest(normalized_truth)
    if expected_truth_sha256 is not None and truth_hash != expected_truth_sha256:
        raise ValueError('Ground truth differs from the frozen acceptance snapshot')
    events: list[Event] = []
    prev = -1
    detached = 0
    measurement_count = 0
    declared_time_mismatch = []
    consistency_errors = []
    for index, frame in enumerate(frames):
        if not isinstance(frame, dict):
            raise ValueError('Each frame must be an object')
        time = integer(frame.get('timeMs'), 'frame.timeMs')
        if time <= prev:
            raise ValueError(f'Frame {index} is not strictly causal/monotonic: {time} <= {prev}')
        if index == 0 and time != 0:
            raise ValueError('Full-video acceptance requires recording from video time 0')
        prev = time
        inp = frame.get('input', {})
        if not isinstance(inp, dict):
            raise ValueError('Frame input must be an object')
        detached += int(bool(inp.get('detachIdentitySource')))
        measures = inp.get('measurements') or inp.get('signals') or []
        if not isinstance(measures, list) or any(not isinstance(m, dict) for m in measures):
            raise ValueError('Measurements must be an array of objects')
        measurement_count += len(measures)
        for m in measures:
            if m.get('timeMs') != time:
                consistency_errors.append(f'Frame {index} contains measurement timestamp mismatch')
        outs = frame.get('outputs')
        if not isinstance(outs, list):
            raise ValueError(f'Frame {index} lacks explicit outputs')
        for out in outs:
            if not isinstance(out, dict):
                raise ValueError('Output event must be an object')
            direction = out.get('direction')
            if direction not in ('ENTER', 'EXIT'):
                raise ValueError(f'Unsupported output direction {direction!r}')
            room = out.get('to') if direction == 'ENTER' else out.get('from')
            gate = out.get('gateId')
            declared = integer(out.get('timeMs'), 'output.timeMs')
            if not isinstance(room, str) or not room or not isinstance(gate, str) or not gate:
                raise ValueError('Output lacks room or gate identity')
            if '#' in gate and gate.split('#', 1)[0] != room:
                consistency_errors.append(f'Frame {index} output has conflicting room/gate')
            ev = Event(len(events), time, declared, room, gate, direction, index)
            if declared != time:
                declared_time_mismatch.append(ev.output_index)
            events.append(ev)
    if max(g['timeMs'] for g in truth) + LATEST_MS > frames[-1]['timeMs']:
        raise ValueError('Recording ends before the final truth deadline')
    hard, preferred = match(truth, events, LATEST_MS), match(truth, events, TARGET_MS)
    used = {v['output_index'] for v in hard}
    matched_ids = {v['truth_id'] for v in hard}
    extras = [asdict(e) for e in events if e.output_index not in used]
    missed = [g['id'] for g in truth if g['id'] not in matched_ids]
    per_event = []
    for g in truth:
        item = next((v for v in hard if v['truth_id'] == g['id']), None)
        per_event.append(dict(id=g['id'], room=g['portalRoomId'], direction=g['direction'],
                              truth_ms=g['timeMs'], matched=item is not None,
                              latency_ms=item['latency_ms'] if item else None))
    passed = not missed and not extras and not consistency_errors
    return dict(
        algorithm=data.get('algorithm', 'UNKNOWN'),
        contract=dict(early_ms=EARLY_MS, latest_ms=LATEST_MS, target_ms=TARGET_MS,
                      expected_labels=expected_labels, timestamp_source='containing frame.timeMs'),
        contract_sha256=digest([EARLY_MS, LATEST_MS, TARGET_MS, expected_labels]),
        truth_sha256=truth_hash,
        frames=len(frames), measurements=measurement_count,
        outputs=len(events), labels=len(truth), hard_matches=len(hard),
        within500_matches=len(preferred), unmatched_outputs=len(extras),
        missed_truth_ids=missed, pairs=hard, per_event=per_event, extra_outputs=extras,
        event_time_differs_from_output_frame=declared_time_mismatch,
        consistency_errors=consistency_errors, identity_source_resets=detached,
        event_contract_status='PASS' if passed else 'FAIL',
        preferred_latency_target_status='PASS' if passed and len(preferred) == len(truth) else 'FAIL',
        wall_clock_latency_status='NOT_EVALUATED',
        independent_video_generalization_status='NOT_EVALUATED',
        full_system_acceptance='NOT_ESTABLISHED',
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recordings', nargs='+', type=Path)
    parser.add_argument('--json-out', type=Path)
    parser.add_argument('--require-target500', action='store_true',
                        help='Strengthen acceptance to require all responses within 500ms')
    args = parser.parse_args()
    results = []
    try:
        for path in args.recordings:
            raw = path.read_bytes()
            scored = audit(json.loads(raw), expected_truth_sha256=EXPECTED_TRUTH_SHA256)
            scored.update(file=str(path), input_sha256=hashlib.sha256(raw).hexdigest())
            results.append(scored)
            print(f"{scored['algorithm']}: {scored['event_contract_status']} "
                  f"hard={scored['hard_matches']}/{scored['labels']} "
                  f"extra={scored['unmatched_outputs']} target500={scored['within500_matches']}")
        if args.json_out:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            args.json_out.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding='utf-8')
        field = 'preferred_latency_target_status' if args.require_target500 else 'event_contract_status'
        return 0 if all(x[field] == 'PASS' for x in results) else 1
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print(f'INVALID_INPUT: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
