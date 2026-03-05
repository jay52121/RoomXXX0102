#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

try:
    from zoneinfo import ZoneInfo

    TZ = ZoneInfo("Asia/Taipei")
except Exception:
    TZ = None


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_HISTORY = ROOT / "dialogueHistory.md"
ENTRY_PATTERN = re.compile(r"^## \[(\d+)\]")


@dataclass
class EntryPayload:
    title: str
    user_text: str
    assistant_text: str
    timestamp: str


def now_text() -> str:
    now = datetime.now(TZ) if TZ else datetime.now()
    return now.strftime("%Y-%m-%d %H:%M:%S")


def read_utf8(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def normalize_block(text: str) -> str:
    # 不改写正文，只保证结尾换行便于 markdown 可读
    return text if text.endswith("\n") else text + "\n"


def next_index(history_path: Path) -> int:
    if not history_path.exists():
        return 1
    max_id = 0
    for line in read_utf8(history_path).splitlines():
        m = ENTRY_PATTERN.match(line)
        if m:
            max_id = max(max_id, int(m.group(1)))
    return max_id + 1


def ensure_header(history_path: Path) -> None:
    if history_path.exists():
        return
    content = (
        "# Dialogue History\n\n"
        "> 由 tools/dialogue_archive.py 维护；内容为原文直存，不做改写。\n\n"
    )
    history_path.write_text(content, encoding="utf-8", newline="\n")


def format_entry(index: int, payload: EntryPayload) -> str:
    user_text = normalize_block(payload.user_text)
    assistant_text = normalize_block(payload.assistant_text)
    return (
        f"## [{index:03d}] {payload.timestamp} - {payload.title}\n\n"
        "**用户原文**：\n"
        "```text\n"
        f"{user_text}"
        "```\n\n"
        "**助手原文**：\n"
        "```text\n"
        f"{assistant_text}"
        "```\n\n"
        "---\n\n"
    )


def append_turn(history_path: Path, payload: EntryPayload) -> int:
    ensure_header(history_path)
    index = next_index(history_path)
    entry = format_entry(index, payload)
    with history_path.open("a", encoding="utf-8", newline="\n") as f:
        f.write(entry)
    return index


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="对话原文归档工具（原文直存，不做改写）"
    )
    sub = parser.add_subparsers(dest="cmd", required=True)

    append_turn_parser = sub.add_parser(
        "append-turn", help="追加一条完整轮次（用户+助手）"
    )
    append_turn_parser.add_argument(
        "--user-file", required=True, type=Path, help="用户原文文件（UTF-8）"
    )
    append_turn_parser.add_argument(
        "--assistant-file", required=True, type=Path, help="助手原文文件（UTF-8）"
    )
    append_turn_parser.add_argument(
        "--title", default="会话归档", help="条目标题（默认: 会话归档）"
    )
    append_turn_parser.add_argument(
        "--time",
        default="",
        help="时间戳，格式 YYYY-MM-DD HH:MM:SS（默认使用当前时间）",
    )
    append_turn_parser.add_argument(
        "--history-file",
        default=str(DEFAULT_HISTORY),
        help="归档文件路径（默认: dialogueHistory.md）",
    )
    return parser


def run_append_turn(args: argparse.Namespace) -> int:
    history_path = Path(args.history_file).resolve()
    payload = EntryPayload(
        title=args.title.strip() or "会话归档",
        user_text=read_utf8(args.user_file),
        assistant_text=read_utf8(args.assistant_file),
        timestamp=args.time.strip() or now_text(),
    )
    index = append_turn(history_path, payload)
    print(f"OK: 已写入 {history_path} 条目 [{index:03d}]")
    return 0


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.cmd == "append-turn":
        return run_append_turn(args)
    parser.error(f"未知命令: {args.cmd}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
