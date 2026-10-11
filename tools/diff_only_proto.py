"""独立差分判定原型（离线）；不调用脚点、PortalV4Core、正式输出或人工事件。

这里只消费门区差分诊断和人体整体遮挡特征，供机制验证。输入文件不含原图，
因此不能重新运行 OpenCV，也不能把本结果称为原生 Kotlin 结果。
"""
from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from statistics import median


@dataclass(frozen=True)
class GateSignal:
    time_ms: int
    room: str
    owner: int
    phase: str
    diff_pixels: int
    depth: float | None
    body_side: float | None
    body_along: float | None
    absorption: float | None
    motion_pixels: int


@dataclass(frozen=True)
class Candidate:
    room: str
    first_ms: int
    last_ms: int
    owner: int
    direction: str
    time_ms: int
    confidence: float
    detail: str


def _med(items: list[GateSignal], attr: str) -> float | None:
    values = [getattr(x, attr) for x in items if getattr(x, attr) is not None]
    return median(values) if values else None


def _quarter_pair(samples: list[GateSignal], attr: str) -> tuple[float | None, float | None]:
    n = max(2, len(samples) // 4)
    return _med(samples[:n], attr), _med(samples[-n:], attr)


def difference_signals(frames: list[dict]) -> list[GateSignal]:
    """仅使用已有差分视觉诊断；避免读取正式输出、人员房间状态或脚点。"""
    seen = []
    for frame in frames:
        for portal in frame.get('portals', []):
            if portal['phase'] not in ('ACTIVE', 'HOLD') or portal['ownerTrack'] is None:
                continue
            owned = portal.get('owned') or {}
            motion = portal.get('motion') or {}
            seen.append(GateSignal(
                frame['timeMs'], portal['gateId'].split('#')[0], portal['ownerTrack'],
                portal['phase'], owned.get('pixels') or 0, owned.get('p50'),
                portal.get('bodySide'), portal.get('bodyAlong'),
                portal.get('bodyInsideRatio'), motion.get('pixels') or 0,
            ))
    return sorted(seen, key=lambda x: (x.time_ms, x.room))


def gate_episodes(signals: list[GateSignal], *, gap_ms: int = 700) -> list[list[GateSignal]]:
    grouped = defaultdict(list)
    for signal in signals:
        grouped[signal.room].append(signal)
    episodes = []
    for room, group in grouped.items():
        current = []
        for s in group:
            if current and s.time_ms - current[-1].time_ms > gap_ms:
                if len(current) >= 4:
                    episodes.append(current)
                current = []
            current.append(s)
        if len(current) >= 4:
            episodes.append(current)
    return sorted(episodes, key=lambda e: e[0].time_ms)


def split_reverse(episode: list[GateSignal]) -> list[list[GateSignal]]:
    """持续深占用时允许一次方向反转，使用人体轮廓法线方向而非脚点。"""
    if episode[-1].time_ms - episode[0].time_ms < 4500:
        return [episode]
    visible = [x for x in episode if x.phase == 'ACTIVE'
               and x.absorption is not None and x.absorption >= .70
               and x.body_side is not None and x.diff_pixels >= 50]
    if len(visible) < 24:
        return [episode]
    # Sliding median smooths detector jitter; turning point must be in central 60%.
    centers = [(visible[i].time_ms, median(x.body_side for x in visible[i-2:i+3]))
               for i in range(2, len(visible)-2)]
    inside = centers[len(centers)//5: 4*len(centers)//5]
    if not inside:
        return [episode]
    at, minimum = min(inside, key=lambda x: x[1])
    pre = [v for t, v in centers if t < at-550]
    post = [v for t, v in centers if t > at+550]
    if not pre or not post or median(pre[:min(5, len(pre))]) - minimum < .023 \
            or median(post[-min(5, len(post)):]) - minimum < .027:
        return [episode]
    left = [s for s in episode if s.time_ms <= at]
    right = [s for s in episode if s.time_ms > at]
    if not left or not right:
        return [episode]
    return [left, right]


def classify(episode: list[GateSignal]) -> Candidate | None:
    """差分深度迁移+门区人体吸收/释放。不读取脚点，且不引用真值。"""
    first, last = episode[0].time_ms, episode[-1].time_ms
    active = [s for s in episode if s.phase == 'ACTIVE' and s.body_side is not None
              and s.absorption is not None and s.absorption >= .28 and s.diff_pixels >= 20]
    if len(active) < 3 or active[-1].time_ms-active[0].time_ms < 200:
        return None
    owner = Counter(s.owner for s in active).most_common(1)[0][0]
    active = [s for s in active if s.owner == owner]
    if len(active) < 3 or active[-1].time_ms-active[0].time_ms < 200:
        return None
    if max(s.absorption or 0 for s in active) < .85:
        return None
    early_side, late_side = _quarter_pair(active, 'body_side')
    early_ratio, late_ratio = _quarter_pair(active, 'absorption')
    visible_depth = [s for s in episode if s.owner == owner and s.depth is not None and s.diff_pixels >= 25]
    early_depth, late_depth = _quarter_pair(visible_depth, 'depth') if visible_depth else (None, None)
    if early_side is None or late_side is None or early_ratio is None or late_ratio is None:
        return None
    side = late_side - early_side
    ratio = late_ratio-early_ratio
    depth = late_depth-early_depth if early_depth is not None and late_depth is not None else 0
    inward = max(0, -side / .025) + max(0, ratio / .25) + max(0, depth / .12)
    outward = max(0, side / .025) + max(0, -ratio / .25) + max(0, -depth / .12)
    # Side drift and absorption corroborate each other. Flat depth alone cannot decide direction.
    inward_trusted = ((side <= -.012 and late_ratio >= .72) or
                      (ratio >= .22 and late_ratio >= .85 and len(active) >= 4) or
                      (side <= -.008 and late_ratio >= .96 and len(active) >= 6))
    early_along, late_along = _quarter_pair(active, 'body_along')
    laterally_withdrawing = (early_along is not None and late_along is not None and
                            abs(late_along-early_along) >= .20)
    outward_trusted = ((side >= .014 and early_ratio >= .65) or
                       (ratio <= -.15 and early_ratio >= .70 and
                        (side >= .002 or laterally_withdrawing or late_ratio <= .70)) or
                       (depth <= -.12 and ratio <= -.05 and early_ratio >= .72))
    # Partial occlusion can hide both ingress direction and the feet. Stable deep
    # foreground which remains absorbed until the human disappears is independent
    # positive evidence, unlike a brief silhouette sweeping sideways past a door.
    strongly_absorbed = [s for s in active if s.absorption >= .90]
    depth_range = (max(s.depth for s in visible_depth) - min(s.depth for s in visible_depth)
                   if visible_depth else 0)
    significant_motion = sum(s.motion_pixels >= max(120, s.diff_pixels * .20) for s in active) >= 3
    absorbed_then_hidden = (len(strongly_absorbed) >= 7 and
                           strongly_absorbed[-1].time_ms-strongly_absorbed[0].time_ms >= 650 and
                           late_ratio >= .88 and
                           last-active[-1].time_ms >= 450 and
                           significant_motion and depth_range >= .065 and
                           any(s.phase == 'HOLD' for s in episode))
    if absorbed_then_hidden:
        inward_trusted = True
    if not inward_trusted and not outward_trusted:
        return None
    if inward_trusted and outward_trusted:
        direction = 'ENTER' if inward >= outward else 'EXIT'
    elif inward_trusted:
        direction = 'ENTER'
    else:
        direction = 'EXIT'
    # A brief tangential sweep across the far end of a portal is not an entry:
    # apparent absorption can increase while the silhouette moves sideways.
    tangential_pass = (early_along is not None and late_along is not None and
                       late_along - early_along >= .27 and side >= -.008)
    if direction == 'ENTER' and (len(active) < 4 or tangential_pass):
        return None
    # Time follows center of consistent evidence, not the (possibly delayed) HOLD expiration.
    # EXIT tends to be visible earlier than full portal clearing.
    point = active[len(active)//2].time_ms
    if direction == 'EXIT' and last-first > 2500:
        point = active[max(1, len(active)*2//5)].time_ms
    if direction == 'ENTER' and last-first > 2500:
        point = active[max(1, len(active)*2//5)].time_ms
    # If a body waits at the doorway and enters later, timestamp the actual
    # inward displacement instead of the earlier static presence.
    if direction == 'ENTER' and side <= -.018 and len(active) >= 12:
        side_half = early_side + side * .50
        cross = next((s for s in active[len(active)//5:] if s.body_side is not None and s.body_side<=side_half), None)
        if cross is not None and cross.time_ms > point:
            point = cross.time_ms
    return Candidate(episode[0].room, first, last, owner, direction, point,
                     max(inward, outward), f'side={side:+.3f} ratio={ratio:+.2f} depth={depth:+.2f}')


def infer(frames: list[dict]) -> tuple[list[Candidate], list[Candidate]]:
    episodes = gate_episodes(difference_signals(frames))
    context = next((f.get('input', {}).get('context') for f in frames
                    if f.get('input', {}).get('context')), None)
    exterior = ({g['room'] for g in context.get('gates', []) if g.get('isExterior')}
                if context is not None else None)
    raw = [c for e in episodes for piece in split_reverse(e) if (c := classify(piece)) is not None]
    raw.sort(key=lambda e: e.time_ms)
    # Rooms are stateful: a given room can only be exited if somebody previously entered,
    # or a previously unseen person is independently emerging from its aperture.
    # No event may be generated simply because a different gate was activated.
    occupancy = Counter()
    active_owner = {}
    selected = []
    for c in raw:
        current = active_owner.get(c.owner)
        if c.direction == 'ENTER':
            if current is not None and current != 'living_room':
                continue
            # Do not decide on the first frames of brief pass-by silhouettes.
            if c.last_ms-c.first_ms < 650 and c.confidence < 2.5:
                continue
            active_owner[c.owner] = c.room
            occupancy[c.room] += 1
            selected.append(c)
        else:
            if current not in (None, c.room) and occupancy[c.room] == 0:
                continue
            # Without an initial population baseline, a previously unseen person
            # can originate from the exterior; arbitrary rooms cannot invent exits.
            if current != c.room and occupancy[c.room] == 0 \
                    and exterior is not None and c.room not in exterior:
                continue
            if occupancy[c.room] > 0:
                occupancy[c.room] -= 1
            active_owner[c.owner] = 'living_room'
            selected.append(c)
    return selected, raw


def match_events(events: list[Candidate], gt: list[dict], *, window_ms: int = 1000):
    # Independent evaluation only: GT is never passed into classify/infer.
    used = set()
    matched = []
    for truth in gt:
        poss = [(abs(c.time_ms-truth['timeMs']), i) for i, c in enumerate(events)
                if i not in used and c.room == truth['portalRoomId']
                and c.direction == truth['direction'] and abs(c.time_ms-truth['timeMs']) <= window_ms]
        if poss:
            _, i = min(poss)
            used.add(i)
            matched.append((truth['id'], i))
    return matched, set(range(len(events))) - used


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path', type=Path)
    ap.add_argument('--json', action='store_true')
    args = ap.parse_args()
    data = json.loads(args.path.read_text(encoding='utf-8'))
    events, raw = infer(data['frames'])
    matches, unmatched = match_events(events, data.get('groundTruth', []), window_ms=data.get('matchWindowMs',1000))
    if args.json:
        print(json.dumps(dict(events=[x.__dict__ for x in events], raw=[x.__dict__ for x in raw],
                              matches=matches, unmatched=list(unmatched)), ensure_ascii=False))
        return
    print(f'SIGNALS={len(difference_signals(data["frames"]))} gate_episodes={len(gate_episodes(difference_signals(data["frames"])))}')
    print(f'CLASSIFIED={len(raw)} EVENT={len(events)} MATCH={len(matches)}/{len(data.get("groundTruth", []))} EXTRAS={len(unmatched)}')
    print('Missed GT:', [(g['id'], data['rooms'].get(g['portalRoomId']), g['direction']) for g in data.get('groundTruth',[]) if g['id'] not in {x[0] for x in matches}])
    for i, c in enumerate(events):
        print(f'{i+1:02d} {c.time_ms/1000:7.2f}s {data["rooms"].get(c.room):4} {c.direction:5} '
              f'owner={c.owner:2d} {c.detail} {"EXTRA" if i in unmatched else "OK"}')


if __name__ == '__main__':
    main()