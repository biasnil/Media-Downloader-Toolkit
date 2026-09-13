"""Persistent settings for the Loudness Normalizer tab."""

import os
import json

CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".loudness_normalizer_config.json")
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