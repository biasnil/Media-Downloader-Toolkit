"""Shared settings that apply to the whole app, not any single tool."""

import os
import json
import tkinter as tk

from Script.utils import get_config_dir, migrate_legacy_file

_LEGACY_CONFIG_PATH = os.path.join(os.path.expanduser("~"), ".media_toolkit_settings.json")
CONFIG_PATH = os.path.join(get_config_dir(), "app_settings.json")
migrate_legacy_file(_LEGACY_CONFIG_PATH, CONFIG_PATH)


def load_app_settings():
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_app_settings(data):
    try:
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f)
    except Exception:
        pass


class AppSettings:
    """Live, shared settings used across tabs. Each field is a Tk variable,
    so any tab reads the current value directly -- no separate refresh step
    needed when the gear-icon dialog changes something.  Call .save() to
    persist the current values to disk (done automatically by the settings
    dialog's Save button, and by the dark-mode switch)."""

    def __init__(self, root):
        data = load_app_settings()
        self.dark_mode = tk.BooleanVar(master=root, value=data.get("dark_mode", False))
        self.embed_thumbnail = tk.BooleanVar(master=root, value=data.get("embed_thumbnail", False))
        self.download_subtitles = tk.BooleanVar(master=root, value=data.get("download_subtitles", False))
        self.subtitle_langs = tk.StringVar(master=root, value=data.get("subtitle_langs", "en"))
        self.speed_limit = tk.StringVar(master=root, value=data.get("speed_limit", ""))
        # "Auto" trusts CustomTkinter's own DPI detection (usually fine on
        # Windows/Mac, unreliable on some Linux desktops/Wayland setups).
        # Any other value is a manual override, e.g. "150%".
        self.ui_scale = tk.StringVar(master=root, value=data.get("ui_scale", "Auto"))
        # How many pasted links (YouTube tab only -- playlist items inside a
        # single link always stay sequential) download at once. Default 1
        # keeps the old one-at-a-time behavior; capped at 5 to limit the risk
        # of getting rate-limited/bot-checked from hitting a site with too
        # many simultaneous connections.
        self.max_concurrent_downloads = tk.StringVar(
            master=root, value=str(data.get("max_concurrent_downloads", 1))
        )
        # Spotify tab: how confident a YouTube match must be (0-100) to
        # download automatically without asking; below this, the
        # continue/skip/retry prompt appears instead. See
        # Script/spotify_match.py for how the score itself is computed.
        self.spotify_confidence_threshold = tk.StringVar(
            master=root, value=str(data.get("spotify_confidence_threshold", 80))
        )
        # Spotify tab: comma-separated suffixes appended to each search
        # query variant (e.g. "Track Artist <tag>"). Defaults match the
        # app's original built-in pair; editing this replaces them entirely
        # rather than adding to them, so search stays predictable.
        self.spotify_search_tags = tk.StringVar(
            master=root,
            value=data.get("spotify_search_tags", "(Official Music Video), (Official Lyric Video)"),
        )

    def save(self):
        save_app_settings({
            "dark_mode": self.dark_mode.get(),
            "embed_thumbnail": self.embed_thumbnail.get(),
            "download_subtitles": self.download_subtitles.get(),
            "subtitle_langs": self.subtitle_langs.get(),
            "speed_limit": self.speed_limit.get(),
            "ui_scale": self.ui_scale.get(),
            "max_concurrent_downloads": self.max_concurrent_downloads.get(),
            "spotify_confidence_threshold": self.spotify_confidence_threshold.get(),
            "spotify_search_tags": self.spotify_search_tags.get(),
        })