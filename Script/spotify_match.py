"""Finds the best-matching YouTube video for a Spotify track (or, for a
playlist link -- see Script/spotify_client.py's docstring on why playlists
only have a title to work with -- the playlist's title standing in for a
track). Runs a set of tag-suffixed query variants (configurable in
Settings, defaulting to "(Official Music Video)" / "(Official Lyric
Video)"), pools every result across all of them, and scores each candidate
on a 0-100 confidence scale. Callers only ever download the single
best-scoring candidate -- never a batch of maybe-matches."""

import difflib

import yt_dlp

SEARCH_RESULTS_PER_QUERY = 3
CONFIDENT_THRESHOLD = 80         # fallback default; Settings > Spotify overrides this per-call
DURATION_TOLERANCE_SEC = 12       # candidates further off than this get penalized harder

DEFAULT_TAGS = ["(Official Music Video)", "(Official Lyric Video)"]


def _normalize(text):
    return "".join(ch.lower() for ch in (text or "") if ch.isalnum() or ch.isspace()).strip()


def build_queries(track, tags=None):
    """The ordered list of search strings to try for this track. A real
    track (title + a distinct artist) gets both Track+Artist and
    Artist+Track orderings crossed with every tag. A title-only item -- a
    playlist's name, or the raw fallback text when Spotify lookup itself
    failed -- just gets each tag once, since there's no separate artist to
    reorder. tags defaults to DEFAULT_TAGS when not given or empty."""
    title = (track.get("title") or "").strip()
    artist = (track.get("artists") or "").strip()
    tags = [t for t in (tags or []) if t] or DEFAULT_TAGS

    if not artist or artist.lower() == title.lower():
        return [f"{title} {tag}" for tag in tags]

    queries = []
    for tag in tags:
        queries.append(f"{title} {artist} {tag}")
        queries.append(f"{artist} {title} {tag}")
    return queries


def search_candidates(query, ydl_opts_base=None):
    """Runs one yt-dlp search (ytsearchN:) and returns flat metadata for
    each result -- title, id, duration, uploader -- without downloading
    anything."""
    opts = dict(ydl_opts_base or {})
    opts.update({"extract_flat": "in_playlist", "quiet": True, "no_warnings": True})
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(f"ytsearch{SEARCH_RESULTS_PER_QUERY}:{query}", download=False)
    return list(info.get("entries") or [])


def score_candidate(track, candidate):
    """Returns a 0-100 confidence score. Combines fuzzy title/artist match
    against the video's title with a duration-closeness penalty (a "Live"
    or "10 Hour Loop" version can look like a perfect title match but is
    clearly wrong once you check the length)."""
    target_text = _normalize(f"{track.get('artists', '')} {track.get('title', '')}")
    cand_text = _normalize(candidate.get("title") or "")
    title_score = difflib.SequenceMatcher(None, target_text, cand_text).ratio()

    target_sec = (track.get("duration_ms") or 0) / 1000
    cand_sec = candidate.get("duration") or 0
    if target_sec and cand_sec:
        diff = abs(target_sec - cand_sec)
        duration_penalty = min(diff / DURATION_TOLERANCE_SEC, 1.0) * 0.4
    else:
        duration_penalty = 0.1  # unknown duration -- small uncertainty penalty either way

    raw = title_score - duration_penalty
    return max(0, min(100, round(raw * 100)))


def find_best_match(track, ydl_opts_base=None, log=None, tags=None):
    """Runs every query variant for this track (or playlist/fallback
    title), pools every result across all of them (deduplicated by video
    id), and scores each one.

    tags is the list of search-tag suffixes to use (Settings > Spotify;
    falls back to DEFAULT_TAGS when None/empty -- see build_queries).

    log, if given, is called with a short diagnostic string whenever a
    query comes back empty or errors out -- without it, a broken/outdated
    yt-dlp YouTube search extractor (this breaks periodically when YouTube
    changes something, independent of anything in this app) looks
    identical to "nothing found", which is impossible to tell apart or fix
    from the log alone.

    Returns (best_candidate_or_None, best_score_0_to_100, all_scored) --
    all_scored is every (score, candidate) pair, best first. best_candidate
    is None only when literally nothing came back from any query."""
    queries = build_queries(track, tags)

    seen_ids = set()
    pooled = []
    for query in queries:
        try:
            results = search_candidates(query, ydl_opts_base)
        except Exception as e:
            if log:
                log(f"YouTube search failed for \"{query}\": {e}")
            continue
        if not results and log:
            log(f"YouTube search returned nothing for \"{query}\"")
        for c in results:
            vid = c.get("id")
            if vid and vid in seen_ids:
                continue
            if vid:
                seen_ids.add(vid)
            pooled.append(c)

    if not pooled:
        return None, 0, []

    scored = [(score_candidate(track, c), c) for c in pooled]
    scored.sort(key=lambda pair: pair[0], reverse=True)
    best_score, best = scored[0]
    return best, best_score, scored