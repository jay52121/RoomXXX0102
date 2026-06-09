#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8000}"

python -m uvicorn server:app --host "$HOST" --port "$PORT"
