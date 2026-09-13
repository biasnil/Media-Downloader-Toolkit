"""Small standalone helpers shared across the app, plus the cancellation signal."""

import os
import sys
import shutil
import platform
import subprocess
from urllib.parse import urlparse, parse_qs

MAX_RETRIES = 3
RETRY_BACKOFF_BASE = 2  # seconds; doubles each retry attempt

TRANSIENT_ERROR_MARKERS = (
    "timed out", "timeout", "connection reset", "connection aborted",
    "temporary failure", "network is unreachable", "unable to download webpage",
    "502", "503", "504", "connection refused", "remote end closed",
)


class DownloadCancelled(Exception):
    """Raised from inside a yt-dlp progress hook to abort the in-progress download."""
    pass


def get_app_dir():
    """Directory containing the running executable (PyInstaller build) or
    this script (running from source). A frozen app's working directory is
    unpredictable, so bundled resources like ffmpeg must be found this way."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def _bundled_ffmpeg_path():
    exe_name = "ffmpeg.exe" if platform.system() == "Windows" else "ffmpeg"
    candidate = os.path.join(get_app_dir(), exe_name)
    return candidate if os.path.isfile(candidate) else None


def check_ffmpeg():
    """True if ffmpeg is usable — either on PATH or bundled next to the app."""
    return shutil.which("ffmpeg") is not None or _bundled_ffmpeg_path() is not None


def get_ffmpeg_location():
    """Path to hand yt-dlp via its ffmpeg_location option. Returns None when
    ffmpeg is already on PATH (yt-dlp will find it itself); returns the
    bundled ffmpeg's path when it's shipped next to the app instead."""
    if shutil.which("ffmpeg"):
        return None
    return _bundled_ffmpeg_path()


def open_folder(path):
    path = os.path.abspath(path)
    try:
        if platform.system() == "Windows":
            os.startfile(path)
        elif platform.system() == "Darwin":
            subprocess.run(["open", path])
        else:
            subprocess.run(["xdg-open", path])
    except Exception:
        pass


def check_existing(title, output_dir, ext="mp3"):
    if not os.path.isdir(output_dir):
        return False
    return f"{title}.{ext}" in os.listdir(output_dir)


def compress_to_ranges(indices):
    """[1,2,3,5,7,8,9] -> '1-3,5,7-9' (playlist_items format yt-dlp expects)."""
    if not indices:
        return ""
    indices = sorted(set(indices))
    parts = []
    start = prev = indices[0]
    for n in indices[1:]:
        if n == prev + 1:
            prev = n
            continue
        parts.append(str(start) if start == prev else f"{start}-{prev}")
        start = prev = n
    parts.append(str(start) if start == prev else f"{start}-{prev}")
    return ",".join(parts)


def is_transient_error(message):
    m = message.lower()
    return any(marker in m for marker in TRANSIENT_ERROR_MARKERS)


def url_has_playlist_param(url):
    """True if the URL's query string includes a 'list=' parameter -- e.g. a
    'watch?v=X&list=Y&index=N' link to one video within a playlist, not just
    a plain 'playlist?list=Y' link. yt-dlp's own default handling of the
    watch+list combination has been inconsistent across versions/reports
    (sometimes only the single video, sometimes the whole playlist), so the
    app forces playlist mode explicitly whenever this parameter is present
    rather than relying on that default."""
    try:
        query = parse_qs(urlparse(url).query)
    except Exception:
        return False
    return bool(query.get("list"))


def canonical_playlist_url(url):
    """Returns a plain 'playlist?list=ID' URL if the given URL has a list
    parameter, else None. Used to probe the *complete* playlist rather than
    whatever partial view a 'watch?v=X&list=Y&index=N' link would otherwise
    give (e.g. starting from the pasted video's position instead of item 1)."""
    try:
        query = parse_qs(urlparse(url).query)
    except Exception:
        return None
    list_ids = query.get("list")
    if not list_ids:
        return None
    return f"https://www.youtube.com/playlist?list={list_ids[0]}"


def parse_rate_limit(text):
    """Turns '500K', '2M', '800000', or '' into bytes/sec (int) for yt-dlp's
    'ratelimit' option, or None for no limit. Returns None on anything it
    can't parse, so a bad value quietly means "unlimited" rather than an error."""
    text = (text or "").strip().upper()
    if not text:
        return None
    try:
        if text.endswith("K"):
            return int(float(text[:-1]) * 1024)
        if text.endswith("M"):
            return int(float(text[:-1]) * 1024 * 1024)
        return int(float(text))
    except ValueError:
        return None