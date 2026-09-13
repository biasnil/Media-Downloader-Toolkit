"""Persistent JSON-lines download history log."""

import os
import json
import time

HISTORY_PATH = os.path.join(os.path.expanduser("~"), ".yt_audio_downloader_history.jsonl")


def log_history_entry(entry):
    """Append one record to the persistent history log."""
    entry = dict(entry)
    entry.setdefault("timestamp", time.strftime("%Y-%m-%d %H:%M:%S"))
    try:
        with open(HISTORY_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")
    except Exception:
        pass  # history logging is best-effort, never block a download over it


def load_history(limit=500):
    if not os.path.exists(HISTORY_PATH):
        return []
    entries = []
    try:
        with open(HISTORY_PATH, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    entries.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    except Exception:
        pass
    return entries[-limit:]