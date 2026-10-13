#!/usr/bin/env python3
"""Run evaluator tests plus Kotlin measurement contracts; NOT detector acceptance."""
from __future__ import annotations
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def main() -> int:
    root = Path(__file__).resolve().parent
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    try:
        subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(root),
                        "-p", "test_*.py", "-v"], check=True, env=env)
        compiler, java = shutil.which("kotlinc"), shutil.which("java")
        if not compiler or not java:
            print("MISSING_TOOL: kotlinc and java are required for motion contracts", file=sys.stderr)
            return 2
        with tempfile.TemporaryDirectory(prefix="sisp-motion-") as work:
            jar = str(Path(work) / "motion-contracts.jar")
            subprocess.run([compiler, str(root / "motion/PortalImageMotion.kt"),
                            str(root / "motion/MotionContractTests.kt"),
                            "-include-runtime", "-d", jar], check=True, env=env)
            subprocess.run([java, "-jar", jar], check=True, env=env)
        print("TOOL_AND_MEASUREMENT_TESTS=PASS; FULL_DETECTOR_ACCEPTANCE=NOT_ESTABLISHED")
        return 0
    except (OSError, subprocess.CalledProcessError) as exc:
        print("CHECK_FAILED: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
