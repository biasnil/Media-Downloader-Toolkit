# Media Downloader Toolkit

A desktop app (Windows/macOS/Linux) for downloading and processing media, built with Python, [CustomTkinter](https://github.com/TomSchimansky/CustomTkinter), and [yt-dlp](https://github.com/yt-dlp/yt-dlp). Everything lives in one window across four tabs.


> For personal use — downloading content you have the right to download. Don't use this to redistribute copyrighted material.

## Features

### 🎵 YouTube
- Paste one or many URLs (one per line) — batch downloads run concurrently
- Download as **MP3** (up to 320kbps) or **MP4**, with a quality/resolution picker
- Automatic playlist detection with a checklist UI to pick exactly which videos to grab (plus quick range select, e.g. `1-50`)
- Skip files that already exist
- Optional separate save folders for audio vs. video
- Optional cookies.txt fallback for when YouTube throws a bot-check/rate-limit error
- Live progress with speed/ETA, cancel button, automatic retry with backoff on transient network errors

### 🌐 Other Sites
- Works with any link `yt-dlp` supports (1,800+ sites) — Instagram, X/Twitter, Reddit, TikTok, Facebook, Vimeo, Twitch, SoundCloud, Tumblr, Bilibili, Threads, and more
- Download as video (MP4) or audio-only (MP3, 320kbps)

### 🔊 Normalizer
- Loudness-normalize a folder of audio files to a target LUFS (e.g. -14 for streaming, -23 for broadcast) using [pyloudnorm](https://github.com/csteinmetz1/pyloudnorm)
- Pick which files to include, batch process, output saved to a `normalized/` subfolder

### 🔄 Converter
- Convert local audio/video files between formats (mp3, wav, flac, aac, m4a, ogg, mp4, mkv, mov, avi, webm) via ffmpeg
- Batch conversion with progress tracking, output saved to a `converted/` subfolder

### Shared across all tabs
- Dark/light mode toggle
- Manual UI scale override (useful on Linux/Wayland where auto-DPI detection can be unreliable)
- Persistent download/conversion history log with a viewer window
- Configurable download speed limit and max concurrent downloads (Settings ⚙)
- Optional thumbnail embedding and subtitle downloads (YouTube/Other Sites)

## Requirements

- Python 3.10+
- [ffmpeg](https://ffmpeg.org/download.html) — installed on PATH, or an `ffmpeg`/`ffmpeg.exe` binary placed next to the app

## Installation

```bash
git clone https://github.com/<your-username>/media-downloader-toolkit.git
cd media-downloader-toolkit
pip install -r requirements.txt
```

## Usage

```bash
python main.py
```

## Project Structure

```
.
├── main.py                  # Entry point — launches the window and all tabs
├── Assets/
│   ├── icon.ico              # App icon (Windows)
│   ├── icon.icns              # App icon (macOS)
│   └── icon.png                # App icon (Linux / window titlebar & taskbar, all platforms)
├── Config/
│   ├── app_settings.py      # Shared app-wide settings (theme, subtitles, speed limit, etc.)
│   ├── config.py            # YouTube tab settings persistence
│   ├── insta_config.py      # Other Sites tab settings persistence
│   └── normalizer_config.py # Normalizer tab settings persistence
└── Script/
    ├── youtube_tab.py       # YouTube tab UI + download orchestration
    ├── other_sites_tab.py   # Other Sites tab
    ├── normalizer_tab.py    # Loudness Normalizer tab
    ├── converter_tab.py     # Format Converter tab
    ├── playlist_dialog.py   # Playlist picker + retry/skip/stop dialog
    ├── settings_dialog.py   # Gear-icon settings window
    ├── history.py           # JSON-lines history log
    ├── history_window.py    # History viewer window
    ├── context_menu.py      # Right-click Cut/Copy/Paste/Select All for text fields
    ├── theme.py              # Color palette + UI scaling
    └── utils.py              # Shared helpers (ffmpeg lookup, app icon, retry logic, etc.)
```

## Building a standalone executable

Packaged with [PyInstaller](https://pyinstaller.org/). The `Assets` folder (app icon) needs to be bundled in via `--add-data`, and `--icon` sets the exe's own icon.

**Windows:**
```bash
pip install pyinstaller
pyinstaller --onefile --windowed --name "MediaDownloaderToolkit" ^
  --icon "Assets/icon.ico" --add-data "Assets;Assets" main.py
```

**macOS/Linux:**
```bash
pip install pyinstaller
pyinstaller --onefile --windowed --name "MediaDownloaderToolkit" \
  --icon "Assets/icon.icns" --add-data "Assets:Assets" main.py
```

> **Note:** `--onefile` bundles everything into a single self-extracting exe, which some antivirus heuristics flag as a "dropper" pattern (false positive — see [Troubleshooting](#troubleshooting)). Using `--onedir` instead avoids that at the cost of shipping a folder instead of one file. Either way, the `--add-data` flag above still applies.

## Troubleshooting

**"ffmpeg not found" warning on launch**
Install ffmpeg and make sure it's on your PATH, or drop an `ffmpeg`/`ffmpeg.exe` binary in the same folder as the app/exe.

**Blocked by YouTube (bot check / rate limit)**
Export a `cookies.txt` from your browser (e.g. via a browser extension) while logged into YouTube, and set it in the YouTube tab. It's only used automatically as a fallback when a download is blocked — not sent by default.

**Antivirus flags the built .exe**
This is a common false positive for PyInstaller `--onefile` builds — bundling a single exe that self-extracts and runs from a temp folder matches generic "dropper" heuristics used by some AV engines, even with no malicious code involved. Building with `--onedir` instead, or code-signing the exe, both reduce this.

**Normalizer says a dependency is missing**
Run `pip install pydub pyloudnorm numpy`. On Python 3.13+, also run `pip install audioop-lts` (the `audioop` module was removed from the standard library).