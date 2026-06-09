from __future__ import annotations

import time
from threading import Lock
from typing import Dict, List, Optional

import numpy as np
import supervision as sv
from fastapi import FastAPI
from pydantic import BaseModel, Field


# ----------------------------
# API models
# ----------------------------
class DetectionIn(BaseModel):
    xyxy: List[float] = Field(..., description="Bounding box in pixels: [x1,y1,x2,y2]")
    conf: float = Field(..., ge=0.0, le=1.0)
    cls: Optional[int] = Field(None, description="Class id (optional). Use 0 for person if you want.")


class TrackRequest(BaseModel):
    camera_id: str = Field("default", description="Which camera/stream this belongs to")
    frame_id: Optional[int] = None
    fps: Optional[float] = Field(None, description="Optional FPS hint for tracker tuning")
    detections: List[DetectionIn] = Field(default_factory=list)


class TrackItem(BaseModel):
    track_id: int
    xyxy: List[float]
    conf: float
    cls: Optional[int] = None


# ----------------------------
# Tracker pool (per camera)
# ----------------------------
app = FastAPI(title="ByteTrack Server (detections-in / tracks-out)")

_trackers: Dict[str, sv.ByteTrack] = {}
_lock = Lock()


def _get_tracker(camera_id: str, fps: Optional[float]) -> sv.ByteTrack:
    # supervision ByteTrack can be constructed without args.
    # If your version supports frame_rate, we pass it; otherwise fallback.
    with _lock:
        if camera_id in _trackers:
            return _trackers[camera_id]

        try:
            if fps is not None:
                _trackers[camera_id] = sv.ByteTrack(frame_rate=float(fps))
            else:
                _trackers[camera_id] = sv.ByteTrack()
        except TypeError:
            # older/newer signature - just use defaults
            _trackers[camera_id] = sv.ByteTrack()

        return _trackers[camera_id]


def _to_sv_detections(dets: List[DetectionIn]) -> sv.Detections:
    if not dets:
        return sv.Detections(
            xyxy=np.empty((0, 4), dtype=np.float32),
            confidence=np.empty((0,), dtype=np.float32),
            class_id=None,
        )

    xyxy = np.asarray([d.xyxy for d in dets], dtype=np.float32)
    conf = np.asarray([d.conf for d in dets], dtype=np.float32)

    # class_id is optional
    if any(d.cls is not None for d in dets):
        cls = np.asarray([(d.cls if d.cls is not None else -1) for d in dets], dtype=np.int32)
    else:
        cls = None

    return sv.Detections(xyxy=xyxy, confidence=conf, class_id=cls)


# ----------------------------
# Endpoints
# ----------------------------
@app.get("/health")
def health():
    return {
        "ok": True,
        "supervision": getattr(sv, "__version__", "unknown"),
        "trackers_alive": list(_trackers.keys()),
    }


@app.post("/reset")
def reset(camera_id: str = "default"):
    with _lock:
        existed = camera_id in _trackers
        _trackers.pop(camera_id, None)
    return {"ok": True, "camera_id": camera_id, "existed": existed}


@app.post("/track")
def track(req: TrackRequest):
    t0 = time.perf_counter()

    tracker = _get_tracker(req.camera_id, req.fps)
    sv_dets = _to_sv_detections(req.detections)

    # Core call: detections in -> tracked detections out
    tracked: sv.Detections = tracker.update_with_detections(sv_dets)

    items: List[TrackItem] = []
    n = len(tracked)

    # tracker_id exists after tracking; if not, we'll output -1
    tracker_ids = getattr(tracked, "tracker_id", None)
    class_ids = getattr(tracked, "class_id", None)
    confs = getattr(tracked, "confidence", None)

    for i in range(n):
        tid = int(tracker_ids[i]) if tracker_ids is not None else -1
        cls = int(class_ids[i]) if class_ids is not None else None
        conf = float(confs[i]) if confs is not None else 0.0

        items.append(
            TrackItem(
                track_id=tid,
                xyxy=tracked.xyxy[i].tolist(),
                conf=conf,
                cls=cls,
            )
        )

    server_ms = (time.perf_counter() - t0) * 1000.0
    return {
        "camera_id": req.camera_id,
        "frame_id": req.frame_id,
        "server_ms": server_ms,
        "tracks": [x.model_dump() for x in items],
    }
