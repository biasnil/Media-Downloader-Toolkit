"""Persistent user settings (last-used folder, format, quality, cookies file)."""

import os
import json

DEFAULT_OUTPUT_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "YT Downloads")
CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".yt_audio_downloader_config.json")


def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_config(data):
    try:
        with open(CONFIG_PATH, "w") as f:
            json.dump(data, f)
    except Exception:
        pass  # non-critical, just skip remembering