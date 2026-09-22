"""Persistent settings for the Loudness Normalizer tab."""

import os
import json

from Script.utils import get_config_dir, migrate_legacy_file

_LEGACY_CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".loudness_normalizer_config.json")
CONFIG_PATH = os.path.join(get_config_dir(), "normalizer_config.json")
migrate_legacy_file(_LEGACY_CONFIG_PATH, CONFIG_PATH)
DEFAULT_TARGET_LUFS = -14.0  # common streaming-loudness target


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