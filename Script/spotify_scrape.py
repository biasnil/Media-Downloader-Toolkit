"""Reads a Spotify track/playlist/album's name -- and, for a single track,
its artist and duration -- straight from the plain public page Spotify
serves for that link. This is the same Open Graph / music meta tag data
Spotify puts on the page so the link previews correctly when shared on
social media: no login, no API app, no Client ID/Secret, no Spotify
Premium requirement. Just the page anyone's browser already loads.

Deliberately limited to what these meta tags expose: a title always: an
artist + duration only for a single track. A playlist or album's
individual song list still isn't available this way either (nor any other
way without a full user login owning that playlist -- see
Script/spotify_client.py's docstring), so those stay title-only here too,
same as the official-API path does for playlists.
"""

import re
import html
import urllib.request

from Script.spotify_client import SpotifyAPIError

USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
# Spotify serves two different responses to open.spotify.com/track|playlist|
# album links: a real desktop-browser User-Agent gets a tiny JS stub that
# tries to bounce it into the native Spotify app first, while a
# link-preview-bot User-Agent -- the ones Slack/Discord/Twitter/Facebook use
# to unfurl a pasted link -- gets the real page with its Open Graph / music
# meta tags straight away, no JS involved. That second path is the entire
# reason those meta tags exist in the first place, so identifying as one of
# those bots isn't a workaround of anything -- it's the documented,
# intended way to read this data without a browser. Confirmed working
# against a real track URL.

_META_RE = re.compile(r'<meta\s+(?:property|name)="([^"]+)"\s+content="([^"]*)"', re.IGNORECASE)

# Spotify's <meta name="description"> follows one of two templates:
#   track/album:  "Listen to <TITLE> on Spotify ..."
#   playlist:     "Playlist \u00b7 <TITLE> \u00b7 <N> items ..."
# Parsing these gives a clean title without the "| Spotify" / "- Album by
# X" suffixes og:title tacks on inconsistently across link types.
_LISTEN_TO_RE = re.compile(r'^Listen to (.+?) on Spotify', re.IGNORECASE)
_PLAYLIST_DESC_RE = re.compile(r'^Playlist\s*[\u00b7:]\s*(.+?)\s*[\u00b7:]', re.IGNORECASE)
_TITLE_SUFFIX_RE = re.compile(r'\s*[|-]\s*(Album by|song and lyrics by|playlist by).*$', re.IGNORECASE)
_TITLE_SITE_RE = re.compile(r'\s*\|\s*Spotify\s*$', re.IGNORECASE)


def fetch_public_metadata(url):
    """Returns {"title":, "artists":, "duration_ms":}. artists/duration_ms
    are "" / 0 when the page doesn't carry them (always true for
    playlists/albums; usually present for tracks). Raises SpotifyAPIError
    if the page can't be read, or has no usable title at all (a different
    locale's description wording, say)."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8", errors="ignore")
    except Exception as e:
        raise SpotifyAPIError(f"Could not read that Spotify page: {e}") from e

    tags = {}
    for name, content in _META_RE.findall(body):
        key = name.lower()
        if key not in tags:  # first occurrence wins (og: and twitter: often duplicate)
            tags[key] = html.unescape(content)

    description = tags.get("description") or tags.get("og:description") or ""

    title = None
    m = _LISTEN_TO_RE.match(description)
    if m:
        title = m.group(1).strip()
    else:
        m = _PLAYLIST_DESC_RE.match(description)
        if m:
            title = m.group(1).strip()

    if not title:
        raw = tags.get("og:title") or tags.get("twitter:title")
        if raw:
            raw = _TITLE_SUFFIX_RE.sub("", raw)
            raw = _TITLE_SITE_RE.sub("", raw)
            title = raw.strip()

    if not title:
        raise SpotifyAPIError("Couldn't find a title on that Spotify page.")

    artist = (tags.get("music:musician_description") or "").strip()

    duration_sec = tags.get("music:duration")
    try:
        duration_ms = int(float(duration_sec) * 1000) if duration_sec else 0
    except ValueError:
        duration_ms = 0

    return {"title": title, "artists": artist, "duration_ms": duration_ms}
