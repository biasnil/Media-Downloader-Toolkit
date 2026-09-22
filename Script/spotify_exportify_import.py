"""Imports a playlist exported from Exportify (exportify.net) as a CSV, or
a ZIP of several CSVs from its "Export All" feature. Exportify handles the
real Spotify login itself, entirely outside this app -- this module only
ever reads the file it hands back, so no Spotify credentials or API access
of any kind are involved here.

Exportify has several forks with slightly different column sets (audio
features included or not, "Artist Name" vs "Artist Name(s)", etc.), so
header lookup below tries a couple of known aliases per field rather than
assuming one fixed layout. Verified against a real export:
    Track URI, Track Name, Album Name, Artist Name(s), Release Date,
    Duration (ms), Popularity, Explicit, Added By, Added At, Genres,
    Record Label, <audio features...>
"""

import os
import csv
import io
import zipfile

TITLE_COLUMNS = ["Track Name"]
ARTIST_COLUMNS = ["Artist Name(s)", "Artist Name"]
DURATION_COLUMNS = ["Duration (ms)", "Track Duration (ms)"]


def _pick(row, candidates):
    for name in candidates:
        value = row.get(name)
        if value:
            return value
    return ""


def _title_from_filename(name):
    """Exportify names each CSV after its playlist (with spaces turned
    into underscores), and the CSV itself carries no playlist-name column
    -- so the filename is the only place to recover it from."""
    base = os.path.splitext(os.path.basename(name))[0]
    return base.replace("_", " ").strip() or "Imported playlist"


def _parse_csv_text(text, title):
    reader = csv.DictReader(io.StringIO(text))
    tracks = []
    for row in reader:
        track_title = _pick(row, TITLE_COLUMNS).strip()
        if not track_title:
            continue
        artists_raw = _pick(row, ARTIST_COLUMNS).strip()
        # Exportify separates multiple artists with ";"; our search/match
        # logic expects the same ", "-joined style used everywhere else.
        artists = ", ".join(a.strip() for a in artists_raw.split(";") if a.strip())
        duration_raw = _pick(row, DURATION_COLUMNS).strip()
        try:
            duration_ms = int(float(duration_raw)) if duration_raw else 0
        except ValueError:
            duration_ms = 0
        tracks.append({"title": track_title, "artists": artists, "duration_ms": duration_ms})
    return title, tracks


def load_exportify_csv(path):
    """Returns (playlist_title, [track dicts]) for a single Exportify CSV
    file. utf-8-sig strips the BOM Exportify's export includes."""
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        text = f.read()
    return _parse_csv_text(text, _title_from_filename(path))


def load_exportify_zip(path):
    """Returns a list of (playlist_title, [track dicts]) -- one per CSV
    file found inside the ZIP ("Export All" produces one CSV per
    playlist). Non-CSV entries are ignored; a CSV with no usable tracks is
    skipped rather than included empty."""
    results = []
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if not name.lower().endswith(".csv"):
                continue
            with zf.open(name) as f:
                text = f.read().decode("utf-8-sig", errors="ignore")
            title, tracks = _parse_csv_text(text, _title_from_filename(name))
            if tracks:
                results.append((title, tracks))
    return results


def load_exportify_file(path):
    """Detects .csv vs .zip and returns a list of (playlist_title, [track
    dicts]) either way -- a single-item list for a plain CSV, one entry
    per playlist for a ZIP. Raises ValueError (with a message meant to be
    shown directly to the user) for an unsupported extension or a file
    with nothing usable in it."""
    ext = os.path.splitext(path)[1].lower()
    if ext == ".csv":
        title, tracks = load_exportify_csv(path)
        if not tracks:
            raise ValueError("No tracks found in that CSV -- is it a valid Exportify export?")
        return [(title, tracks)]
    elif ext == ".zip":
        results = load_exportify_zip(path)
        if not results:
            raise ValueError("No CSV files with tracks found inside that ZIP.")
        return results
    else:
        raise ValueError("Choose a .csv (single playlist) or .zip (Export All) file from Exportify.")
