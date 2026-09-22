"""Persistent settings for the Instagram/X tab. Uses the same config file path
as the original standalone script, so existing saved settings carry over."""

import os
import json

from Script.utils import get_config_dir, migrate_legacy_file

_LEGACY_CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".insta_downloader_config.json")
CONFIG_PATH = os.path.join(get_config_dir(), "other_sites_config.json")
migrate_legacy_file(_LEGACY_CONFIG_PATH, CONFIG_PATH)
DEFAULT_SAVE_FOLDER = os.path.join(os.path.expanduser("~"), "Downloads")


def load_config():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_config(data):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception:
        pass