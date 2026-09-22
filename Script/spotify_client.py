"""Spotify link parsing, plus the shared error type other Spotify-related
modules use for anything that goes wrong reading Spotify data.

This app no longer talks to Spotify's authenticated Web API at all --
Client ID/Secret/OAuth login were removed entirely. What's left instead:
  - Script/spotify_scrape.py reads a link's public page directly (title,
    and for a track, artist + duration) -- no credentials needed.
  - Script/spotify_exportify_import.py reads a CSV/ZIP exported from
    https://exportify.net for full playlist contents -- Exportify handles
    the real Spotify login itself, entirely outside this app.
Keeping this file this small is deliberate, not an oversight.
"""

import re


class SpotifyAPIError(Exception):
    """Raised with a human-readable message for anything that goes wrong
    reading Spotify data -- callers show str(e) directly to the user."""
    pass


# Matches open.spotify.com/track|playlist|album/<id>, with or without the
# "/intl-xx/" locale prefix Spotify sometimes adds to shared links.
_URL_RE = re.compile(r"open\.spotify\.com/(?:intl-[a-z]{2}/)?(track|playlist|album)/([A-Za-z0-9]+)")


def parse_spotify_url(url):
    """Returns (kind, id), e.g. ('playlist', '37i9dQZF1...'), or (None, None)
    if the URL isn't a recognizable Spotify track/playlist/album link."""
    match = _URL_RE.search(url or "")
    if not match:
        return None, None
    return match.group(1), match.group(2)