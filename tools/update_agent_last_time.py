#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from datetime import datetime

try:
    from zoneinfo import ZoneInfo
    TZ = ZoneInfo("Asia/Taipei")
except Exception:
    TZ = None

def main() -> int:
    if len(sys.argv) < 3:
        print("用法: update_agent_last_time.py <changed_file> <time_file>", file=sys.stderr)
        return 2

    time_file = sys.argv[2]
    os.makedirs(os.path.dirname(time_file) or ".", exist_ok=True)

    now = datetime.now(TZ) if TZ else datetime.now()
    stamp = now.strftime("%Y-%m-%d %H:%M:%S %z").strip() + "\n"

    tmp = time_file + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(stamp)
    os.replace(tmp, time_file)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
