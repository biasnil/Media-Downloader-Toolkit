"""Persistent user settings (last-used folder, format, quality, cookies file)."""

import os
import json

from Script.utils import get_config_dir, migrate_legacy_file

DEFAULT_OUTPUT_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "YT Downloads")
_LEGACY_CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".yt_audio_downloader_config.json")
CONFIG_PATH = os.path.join(get_config_dir(), "youtube_config.json")
migrate_legacy_file(_LEGACY_CONFIG_PATH, CONFIG_PATH)


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