# ByteTrack Service Handoff For Codex

## 1. Context

The Android project is `RoomXXX0102`. It analyzes video frames, detects people and poses, keeps room state, draws ROI overlays, and runs higher-level logic such as room enter/exit, hand ROI, and device pointing.

The ByteTrack service was introduced because the earlier local lightweight tracker could not keep stable IDs well enough under multi-person scenes, temporary occlusion, missing frames, and box jitter.

This service is intentionally narrow:

```text
detections in -> ByteTrack -> tracks out
```

It is not:

```text
image/video in -> YOLO -> ByteTrack -> business result
```

The Android app still runs detection. The service only receives already detected person boxes and returns `track_id` values.

## 2. Original Server Location

Original Windows-side server directory:

```text
C:\Users\YZ\bytetrack_server
```

Known files:

```text
server.py
track_smoke_test.py
yolo11n.pt
.venv
```

Notes:

- `server.py` is the actual FastAPI service.
- `.venv` is a Windows virtual environment, about 1.1GB.
- `yolo11n.pt` was only for an old smoke test. Current `server.py` does not use it.
- Do not copy the Windows `.venv` to Linux. Recreate the environment on Linux.

## 3. Linux Package In This Repo

Package path:

```text
D:\Users\YZ\AndroidStudioProjects\RoomXXX0102\artifacts\bytetrack_server_linux.zip
```

Expanded package directory:

```text
D:\Users\YZ\AndroidStudioProjects\RoomXXX0102\artifacts\bytetrack_server_linux
```

Package files:

```text
server.py
requirements-linux.txt
start.sh
README_DEPLOY_LINUX.md
CODEX_HANDOFF.md
```

The package is tiny because it does not include `.venv`, PyTorch, Ultralytics, or YOLO model files.

## 4. Server API

### Health

```http
GET /health
```

Expected response:

```json
{
  "ok": true,
  "supervision": "0.27.0",
  "trackers_alive": []
}
```

### Reset

```http
POST /reset
```

The endpoint has a default `camera_id="default"`. Current Android client calls `/reset` without query parameters.

### Track

```http
POST /track
```

Request example:

```json
{
  "camera_id": "livingroom_phone",
  "frame_id": 1,
  "frame_width": 1280,
  "frame_height": 720,
  "detections": [
    {
      "xyxy": [110, 105, 210, 325],
      "conf": 0.91,
      "cls": 0
    }
  ]
}
```

Response example:

```json
{
  "camera_id": "livingroom_phone",
  "frame_id": 1,
  "server_ms": 1.2,
  "tracks": [
    {
      "track_id": 1,
      "xyxy": [110.0, 105.0, 210.0, 325.0],
      "conf": 0.91,
      "cls": 0
    }
  ]
}
```

`frame_width` and `frame_height` are currently sent by Android. `server.py` does not declare them in `TrackRequest`; Pydantic v2 ignores extra fields by default, so the current service still accepts the request.

## 5. Linux Deployment

On the Linux target:

```bash
unzip bytetrack_server_linux.zip
cd bytetrack_server_linux
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements-linux.txt
```

If `python3.11` is unavailable:

```bash
python3 --version
python3 -m venv .venv
```

Start:

```bash
source .venv/bin/activate
chmod +x start.sh
./start.sh
```

Equivalent command:

```bash
python -m uvicorn server:app --host 0.0.0.0 --port 8000
```

Verify locally:

```bash
curl http://127.0.0.1:8000/health
```

Verify from the LAN:

```bash
curl http://<linux-ip>:8000/health
```

If the phone cannot reach the service:

- Confirm the phone and Linux host are on the same LAN.
- Confirm the service is started with `--host 0.0.0.0`.
- Open TCP port `8000` in the Linux firewall.
- Confirm the Android app is pointing to the Linux host IP.

## 6. Android Client Connection Points

Relevant files:

```text
app/src/main/java/com/example/roomxxx0102/logic/tracker/RemoteByteTrackEngine.kt
app/src/main/java/com/example/roomxxx0102/logic/tracker/TrackClient.kt
app/src/main/java/com/example/roomxxx0102/ui/settings/SettingsHomeFragment.kt
app/src/main/java/com/example/roomxxx0102/logic/analyzer/YoloPoseAnalyzer.kt
```

Current hardcoded base URL in `RemoteByteTrackEngine.kt`:

```kotlin
private val baseUrl: String = "http://192.168.50.161:8000"
```

Current hardcoded health check in `SettingsHomeFragment.kt`:

```kotlin
.url("http://192.168.50.161:8000/health")
```

If the Linux target IP is different, update these locations or, preferably, introduce a shared setting.

Recommended future change:

- Add ByteTrack base URL to `AppSettings`.
- Add a setting UI input for the URL.
- Use the same value in `RemoteByteTrackEngine` and `SettingsHomeFragment`.
- Keep the old URL as a default if needed.

## 7. Android Runtime Flow

Current flow:

```text
YoloPoseAnalyzer
  -> choose SimpleTrackerEngine or RemoteByteTrackEngine based on settings
  -> RemoteByteTrackEngine
  -> TrackClient
  -> POST /track
  -> parse TrackResponse
  -> convert to TrackResult
  -> lock/unlock, ROI, room state, overlays continue using TrackResult
```

`RemoteByteTrackEngine` has fallback behavior:

- If the service is unavailable
- If requests fail
- If remote tracking enters failure cooldown

Then it falls back to `SimpleTrackerEngine`. The app should not completely stop working, but ID stability may be worse.

## 8. Size Notes

Current Linux deployment zip is small because it is source-only.

Original Windows server folder:

```text
about 1.1GB
```

Reason:

```text
.venv dominates the size
```

Current Linux package does not include:

- Windows `.venv`
- `torch`
- `torchvision`
- `ultralytics`
- YOLO models

If the service later needs to run detection on the server, expect the environment to grow from small Python web/tracking dependencies to hundreds of MB or several GB.

## 9. Important Engineering Notes

1. Keep the ByteTrack service business-free. It should return tracks, not room decisions.
2. Android must send a stable `camera_id` for one continuous stream.
3. Android should keep `frame_id` monotonic per stream.
4. All boxes sent to `/track` must use the same coordinate system.
5. Do not mix preview coordinates, original bitmap coordinates, rotated coordinates, or cropped coordinates unless explicitly transformed.
6. If rotation, crop, scale, or model input mapping changes, revalidate `/track` box coordinates first.
7. Send only person detections unless there is a deliberate reason to track other classes.
8. `track_id` is only a short-term tracking ID. Do not treat it as a permanent human identity.
9. Business logic should still defend against ID reuse and person switching.
10. If `/health` works but IDs are unstable, inspect detection jitter and coordinate consistency before changing ByteTrack.
11. If `/track` is slow or timing out, check Android `TrackClient` timeout first. It is currently intentionally short for analyzer use.
12. The first deployment test should use one person walking, then multi-person crossing, then doorway occlusion.
13. When debugging Android, search logcat for tag `ByteTrack`.
14. For lock/unlock issues, search logcat for `RoomLockDiag`.
15. The next high-value Android change is replacing the hardcoded service URL with a user-configurable setting.

## 10. Quick Smoke Test Payload

After service start, test `/track`:

```bash
curl -X POST http://127.0.0.1:8000/track \
  -H "Content-Type: application/json" \
  -d '{"camera_id":"cam1","frame_id":1,"detections":[{"xyxy":[110,105,210,325],"conf":0.91,"cls":0}]}'
```

Expected result: response contains `tracks` and each item has a `track_id`.
