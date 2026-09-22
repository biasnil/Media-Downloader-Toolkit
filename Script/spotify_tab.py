"""The Spotify tab -- downloads a track/playlist/album by matching it to
its closest equivalent on YouTube and downloading that, since Spotify's
own audio streams are DRM-protected and not something any tool (this one
included) can pull directly. No Spotify credentials of any kind are used
anywhere in this file.

Two ways in:
  1. Paste a Spotify link (or just type a song/artist/playlist name) --
     read via the link's own public page (Script/spotify_scrape.py, no
     credentials needed), matched to the single best YouTube result
     (Script/spotify_match.py), and downloaded. A playlist/album link only
     ever yields its *name* this way, matched as one item, same as a
     track -- Spotify doesn't expose a playlist's actual contents to any
     app anymore (see spotify_scrape.py's docstring for why), and this app
     doesn't talk to Spotify's authenticated API at all to try.
  2. Import a CSV (or ZIP of several, from "Export All") from Exportify
     (https://exportify.net) -- a separate, established tool where *you*
     log into your own Spotify account, entirely outside this app, and it
     hands back the playlist's real per-track contents.
     Script/spotify_exportify_import.py just reads that file; every track
     in it goes through the same matching + download pipeline as
     everything else, with a checklist to pick which ones.
"""

import os
import re
import threading
import concurrent.futures
import webbrowser
import tkinter as tk
from tkinter import messagebox, filedialog

import customtkinter as ctk
import yt_dlp

import Script.theme as theme
from Script.context_menu import add_context_menu
from Script.history import log_history_entry
from Script.history_window import open_history_window
from Script.spotify_client import parse_spotify_url, SpotifyAPIError
from Script.spotify_scrape import fetch_public_metadata
from Script.spotify_match import find_best_match, CONFIDENT_THRESHOLD
from Script.spotify_match_prompt import confirm_low_confidence_match
from Script.spotify_playlist_dialog import confirm_spotify_tracks, confirm_exportify_playlist_choice
from Script.spotify_exportify_import import load_exportify_file
from Script.utils import get_ffmpeg_location, open_folder, parse_rate_limit
from Config.spotify_config import DEFAULT_SAVE_FOLDER, load_config, save_config

EXPORTIFY_URL = "https://exportify.net"


def _safe_filename(name):
    name = re.sub(r'[\\/:*?"<>|]', "_", name)
    return name.strip()[:150] or "track"


def _item_label(track):
    artists = track.get("artists")
    title = track.get("title") or "Unknown"
    return f"{artists} - {title}" if artists else title


class SpotifyTab:
    MP3_QUALITY_OPTIONS = [
        "Best available (no re-encode)",
        "320 kbps",
        "256 kbps",
        "192 kbps",
        "128 kbps",
    ]

    def __init__(self, master, root, settings):
        self.master = master
        self.root = root
        self.settings = settings  # shared thumbnail-embed/speed-limit/concurrency settings

        config = load_config()
        self.save_folder = tk.StringVar(value=config.get("save_folder", DEFAULT_SAVE_FOLDER))
        self.quality = tk.StringVar(value=config.get("quality", self.MP3_QUALITY_OPTIONS[0]))
        self.skip_existing = tk.BooleanVar(value=True)

        self.is_downloading = False
        self.cancel_event = threading.Event()
        self.progress_var = tk.DoubleVar(value=0)
        self.completed = 0
        self.last_output_dir_used = self.save_folder.get()

        self._build_ui()

    # ------------------------------------------------------------------ UI

    def _secondary_button(self, master, text, command, state="normal"):
        return ctk.CTkButton(
            master, text=text, command=command, state=state,
            fg_color=theme.SURFACE, hover_color=theme.SURFACE_HOVER,
            text_color=theme.TEXT, border_width=1, border_color=theme.BORDER,
        )

    def _primary_button(self, master, text, command, state="normal"):
        return ctk.CTkButton(
            master, text=text, command=command, state=state,
            fg_color=theme.RED, hover_color=theme.RED_HOVER, text_color=theme.WHITE,
            font=ctk.CTkFont(weight="bold"),
        )

    def _section_label(self, master, text):
        ctk.CTkLabel(
            master, text=text, text_color=theme.TEXT, font=ctk.CTkFont(size=13, weight="bold"),
            anchor="w",
        ).pack(anchor="w", padx=16, pady=(10, 2))

    def _build_ui(self):
        pad = {"padx": 16, "pady": 6}
        body = ctk.CTkFrame(self.master, fg_color=theme.BG, corner_radius=0)
        body.pack(fill="both", expand=True)

        self._section_label(body, "Spotify link, or just type a song/artist/playlist name")
        self.url_entry = ctk.CTkEntry(
            body, fg_color=theme.SURFACE, text_color=theme.TEXT, border_color=theme.BORDER,
        )
        self.url_entry.pack(fill="x", padx=16, pady=(0, 6))
        add_context_menu(self.url_entry)
        ctk.CTkLabel(
            body,
            text="Spotify can't be downloaded from directly (DRM) -- everything here is matched "
                 "to its closest equivalent on YouTube instead. A playlist or album link only "
                 "gets its name this way, matched as one item, same as a track -- for a "
                 "playlist's actual songs, use Import below instead.",
            text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), wraplength=680, justify="left", anchor="w",
        ).pack(anchor="w", padx=16)

        exportify_frame = ctk.CTkFrame(body, fg_color=theme.SURFACE, border_width=1, border_color=theme.BORDER)
        exportify_frame.pack(fill="x", **pad)
        ctk.CTkLabel(
            exportify_frame,
            text="Want a playlist's actual songs, not just a name match? Export it from "
                 "Exportify (you log into your own Spotify account there -- nothing here needs "
                 "any Spotify credentials at all), then import the file it gives you.",
            text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), wraplength=680, justify="left", anchor="w",
        ).pack(anchor="w", padx=12, pady=(10, 8))
        exportify_btn_row = ctk.CTkFrame(exportify_frame, fg_color="transparent")
        exportify_btn_row.pack(fill="x", padx=12, pady=(0, 10))
        self._secondary_button(
            exportify_btn_row, "Go to Exportify", lambda: webbrowser.open(EXPORTIFY_URL),
        ).pack(side="left")
        self.import_btn = self._secondary_button(
            exportify_btn_row, "Import CSV/ZIP...", self._import_exportify_click,
        )
        self.import_btn.pack(side="left", padx=(6, 0))

        folder_frame = ctk.CTkFrame(body, fg_color="transparent")
        folder_frame.pack(fill="x", **pad)
        ctk.CTkLabel(folder_frame, text="Save to:", width=70, anchor="w", text_color=theme.TEXT).pack(side="left")
        folder_entry = ctk.CTkEntry(
            folder_frame, textvariable=self.save_folder, fg_color=theme.SURFACE,
            text_color=theme.TEXT, border_color=theme.BORDER,
        )
        folder_entry.pack(side="left", fill="x", expand=True, padx=6)
        add_context_menu(folder_entry)
        self._secondary_button(folder_frame, "Browse...", self._choose_folder).pack(side="left")

        options_frame = ctk.CTkFrame(body, fg_color="transparent")
        options_frame.pack(fill="x", **pad)
        ctk.CTkLabel(options_frame, text="Quality:", text_color=theme.TEXT).pack(side="left")
        ctk.CTkOptionMenu(
            options_frame, variable=self.quality, values=self.MP3_QUALITY_OPTIONS,
            fg_color=theme.SURFACE, button_color=theme.RED, button_hover_color=theme.RED_HOVER,
            text_color=theme.TEXT, dropdown_fg_color=theme.SURFACE, dropdown_text_color=theme.TEXT,
            dropdown_hover_color=theme.SURFACE_HOVER, width=200,
        ).pack(side="left", padx=(6, 20))
        ctk.CTkCheckBox(
            options_frame, text="Skip songs that already exist", variable=self.skip_existing,
            fg_color=theme.RED, hover_color=theme.RED_HOVER, checkmark_color=theme.WHITE,
            text_color=theme.TEXT,
        ).pack(side="left")

        btn_frame = ctk.CTkFrame(body, fg_color="transparent")
        btn_frame.pack(pady=10)
        self.download_btn = self._primary_button(btn_frame, "Download", self._start_download)
        self.download_btn.pack(side="left", padx=4)
        self.cancel_btn = self._secondary_button(
            btn_frame, "Cancel", self._cancel_download, state="disabled"
        )
        self.cancel_btn.pack(side="left", padx=4)
        self.open_folder_btn = self._secondary_button(
            btn_frame, "Open Folder", self._open_output_folder, state="disabled"
        )
        self.open_folder_btn.pack(side="left", padx=4)
        self._secondary_button(
            btn_frame, "History", lambda: open_history_window(self.root)
        ).pack(side="left", padx=4)

        progress_frame = ctk.CTkFrame(body, fg_color="transparent")
        progress_frame.pack(fill="x", **pad)
        self.progress_bar = ctk.CTkProgressBar(progress_frame, progress_color=theme.RED, fg_color=theme.SURFACE)
        self.progress_bar.set(0)
        self.progress_bar.pack(fill="x")

        self.status_var = tk.StringVar(value="Idle.")
        ctk.CTkLabel(
            body, textvariable=self.status_var, text_color=theme.TEXT_MUTED, anchor="w", justify="left",
        ).pack(fill="x", **pad)

        self._section_label(body, "Log")
        self.log_box = ctk.CTkTextbox(
            body, fg_color=theme.SURFACE, text_color=theme.TEXT, border_width=1,
            border_color=theme.BORDER, scrollbar_button_color=theme.RED,
            scrollbar_button_hover_color=theme.RED_HOVER,
        )
        self.log_box.configure(state="disabled")
        self.log_box.pack(fill="both", expand=True, padx=16, pady=(0, 16))
        add_context_menu(self.log_box)

    def _log(self, msg):
        self.log_box.configure(state="normal")
        self.log_box.insert("end", msg + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def _choose_folder(self):
        folder = filedialog.askdirectory(initialdir=self.save_folder.get() or os.path.expanduser("~"))
        if folder:
            self.save_folder.set(folder)

    def _open_output_folder(self):
        if self.last_output_dir_used and os.path.isdir(self.last_output_dir_used):
            open_folder(self.last_output_dir_used)

    def _persist_settings(self):
        save_config({
            "save_folder": self.save_folder.get().strip(),
            "quality": self.quality.get(),
        })

    def _build_ydl_opts_base(self):
        opts = {}
        ffmpeg_location = get_ffmpeg_location()
        if ffmpeg_location:
            opts["ffmpeg_location"] = ffmpeg_location
        rate_limit = parse_rate_limit(self.settings.speed_limit.get())
        if rate_limit:
            opts["ratelimit"] = rate_limit
        return opts

    def _enter_downloading_state(self, status_text):
        self.cancel_event.clear()
        self.progress_var.set(0)
        self.progress_bar.set(0)
        self.completed = 0
        self.is_downloading = True
        self.open_folder_btn.configure(state="disabled")
        self.download_btn.configure(state="disabled")
        self.import_btn.configure(state="disabled")
        self.cancel_btn.configure(state="normal")
        self.status_var.set(status_text)

    # --------------------------------------------------------------- Download

    def _start_download(self):
        if self.is_downloading:
            return

        url = self.url_entry.get().strip()
        if not url:
            messagebox.showinfo("No link", "Paste a Spotify link, or type a song/artist/playlist name.")
            return

        folder = self.save_folder.get().strip()
        if not folder:
            messagebox.showwarning("Missing folder", "Please choose a save folder.")
            return
        os.makedirs(folder, exist_ok=True)

        self._persist_settings()
        self.last_output_dir_used = folder
        self._enter_downloading_state("Looking up link...")

        thread = threading.Thread(
            target=self._download_worker, args=(url, folder, self.quality.get()), daemon=True,
        )
        thread.start()

    def _cancel_download(self):
        if not self.is_downloading:
            return
        self.cancel_event.set()
        self.cancel_btn.configure(state="disabled")
        self.status_var.set("Cancelling... (finishing current track)")

    def _download_worker(self, url, out_dir, quality):
        # Try it directly first, on the off chance yt-dlp's own (very
        # limited) Spotify support can handle this particular link. In
        # practice this basically always returns metadata only -- Spotify's
        # actual audio streams are DRM-protected regardless -- but it's one
        # quick, cheap probe before falling back to reading the page below.
        try:
            probe_opts = {"quiet": True, "no_warnings": True, "extract_flat": True}
            with yt_dlp.YoutubeDL(probe_opts) as probe:
                probe.extract_info(url, download=False)
            self.root.after(
                0, self._log,
                "Direct extraction returned metadata only, as expected (Spotify audio is "
                "DRM-protected). Matching on YouTube instead...",
            )
        except Exception:
            self.root.after(
                0, self._log,
                "Link isn't directly downloadable (expected). Matching on YouTube instead...",
            )

        if self.cancel_event.is_set():
            self.root.after(0, self._finish, True)
            return

        kind, spotify_id = parse_spotify_url(url)
        items = None
        batch_title = None

        if kind:
            try:
                meta = fetch_public_metadata(url)
                items = [meta]
                batch_title = _item_label(meta) if meta.get("artists") else meta["title"]
                if kind == "playlist":
                    self.root.after(
                        0, self._log,
                        f"Read the playlist's name from its page: '{meta['title']}'. Its actual "
                        "track list isn't readable this way (see the note above about Import "
                        "for that).",
                    )
                elif kind == "album":
                    self.root.after(
                        0, self._log,
                        f"Read the album's title from its page: '{meta['title']}'.",
                    )
            except SpotifyAPIError as e:
                self.root.after(
                    0, self._log,
                    f"Couldn't read that Spotify page ({e}) -- falling back to searching the "
                    "pasted text directly.",
                )
        else:
            self.root.after(
                0, self._log,
                "Not a recognizable Spotify link -- searching YouTube for the pasted text directly.",
            )

        if not items:
            items = [{"title": url, "artists": "", "duration_ms": 0}]
            batch_title = url

        self._run_batch(items, out_dir, quality)

    # ------------------------------------------------------------ Exportify

    def _import_exportify_click(self):
        if self.is_downloading:
            return

        path = filedialog.askopenfilename(
            title="Select an Exportify export",
            filetypes=[
                ("Exportify export", "*.csv *.zip"),
                ("CSV files", "*.csv"),
                ("ZIP files", "*.zip"),
                ("All files", "*.*"),
            ],
        )
        if not path:
            return

        folder = self.save_folder.get().strip()
        if not folder:
            messagebox.showwarning("Missing folder", "Please choose a save folder.")
            return
        os.makedirs(folder, exist_ok=True)

        self._persist_settings()
        self.last_output_dir_used = folder
        self._enter_downloading_state("Reading file...")
        self.import_btn.configure(text="Importing...")

        thread = threading.Thread(
            target=self._import_exportify_worker, args=(path, folder, self.quality.get()), daemon=True,
        )
        thread.start()

    def _import_exportify_worker(self, path, out_dir, quality):
        try:
            playlists = load_exportify_file(path)
        except ValueError as e:
            self.root.after(0, self._log, f"Couldn't read that file: {e}")
            self.root.after(0, self._finish, False)
            return
        except Exception as e:
            self.root.after(0, self._log, f"Unexpected error reading that file: {e}")
            self.root.after(0, self._finish, False)
            return

        if len(playlists) == 1:
            title, tracks = playlists[0]
        else:
            choice = confirm_exportify_playlist_choice(self.root, playlists)
            if self.cancel_event.is_set():
                self.root.after(0, self._finish, True)
                return
            if choice is None:
                self.root.after(0, self._log, "Import cancelled.")
                self.root.after(0, self._finish, False)
                return
            title, tracks = playlists[choice]

        if not tracks:
            self.root.after(0, self._log, f"No tracks found in '{title}'.")
            self.root.after(0, self._finish, False)
            return

        selected = confirm_spotify_tracks(self.root, tracks, title)
        if self.cancel_event.is_set():
            self.root.after(0, self._finish, True)
            return
        if selected is None:
            self.root.after(0, self._log, f"Skipped: {title}")
            self.root.after(0, self._finish, False)
            return

        self.root.after(
            0, self._log, f"Importing '{title}': matching and downloading {len(selected)} track(s)",
        )
        self._run_batch(selected, out_dir, quality)

    # --------------------------------------------------------- Shared batch

    def _run_batch(self, items, out_dir, quality):
        """Runs the search-match-download pipeline over any list of track
        dicts, concurrently up to the shared max-downloads setting. Used
        for both a single item from the link field and a full Exportify
        import."""
        total = len(items)
        try:
            max_workers = int(self.settings.max_concurrent_downloads.get())
        except (ValueError, AttributeError):
            max_workers = 1
        max_workers = max(1, min(max_workers, total, 5))

        try:
            threshold = int(self.settings.spotify_confidence_threshold.get())
        except (ValueError, AttributeError):
            threshold = CONFIDENT_THRESHOLD
        threshold = max(0, min(100, threshold))
        tags = [t.strip() for t in self.settings.spotify_search_tags.get().split(",") if t.strip()]

        ydl_opts_base = self._build_ydl_opts_base()
        dialog_lock = threading.Lock()  # only one continue/skip/retry prompt on screen at once
        remembered_action = [None]  # boxed so nested closures can write to it

        def worker(i, track):
            if self.cancel_event.is_set():
                self.root.after(0, self._on_track_done, total)
                return
            try:
                self._resolve_and_download(
                    track, i, total, out_dir, quality, ydl_opts_base,
                    dialog_lock, remembered_action, threshold, tags,
                )
            finally:
                self.root.after(0, self._on_track_done, total)

        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = [executor.submit(worker, i, t) for i, t in enumerate(items, 1)]
            concurrent.futures.wait(futures)

        cancelled = self.cancel_event.is_set()
        if cancelled:
            self.root.after(0, self._log, "Cancelled by user.")
        self.root.after(0, self._finish, cancelled)

    def _on_track_done(self, total):
        self.completed += 1
        fraction = self.completed / total if total else 0
        self.progress_var.set(fraction * 100)
        self.progress_bar.set(fraction)

    def _resolve_and_download(
        self, track, index, total, out_dir, quality, ydl_opts_base,
        dialog_lock, remembered_action, threshold, tags,
    ):
        label = _item_label(track)
        safe_name = _safe_filename(label)
        out_path_no_ext = os.path.join(out_dir, safe_name)

        if self.skip_existing.get() and os.path.isfile(out_path_no_ext + ".mp3"):
            self.root.after(0, self._log, f"[{index}/{total}] Skipped (already exists): {label}")
            log_history_entry({
                "source": "spotify", "url": "", "title": label,
                "status": "skipped", "detail": "already exists",
            })
            return

        if self.cancel_event.is_set():
            return

        search_log = lambda msg: self.root.after(0, self._log, f"[{index}/{total}]   {msg}")

        self.root.after(0, self._log, f"[{index}/{total}] Searching YouTube for: {label}")
        best, score, _ = find_best_match(track, ydl_opts_base, log=search_log, tags=tags)

        while best is None or score < threshold:
            if self.cancel_event.is_set():
                return

            # A remembered choice only applies when there's actually
            # something to act on the same way -- "continue" with no
            # candidate at all still needs its own prompt.
            with dialog_lock:
                if remembered_action[0] is not None and not (
                    remembered_action[0] == "continue" and best is None
                ):
                    action = remembered_action[0]
                else:
                    action, remember = confirm_low_confidence_match(
                        self.root, f"[{index}/{total}] {label}", best, score
                    )
                    if remember and action in ("skip", "continue") and best is not None:
                        remembered_action[0] = action
                        self.root.after(
                            0, self._log,
                            f"[{index}/{total}] Remembering '{action}' for the rest of this batch.",
                        )

            if action == "retry":
                self.root.after(0, self._log, f"[{index}/{total}] Retrying search: {label}")
                best, score, _ = find_best_match(track, ydl_opts_base, log=search_log, tags=tags)
                continue
            elif action == "continue" and best is not None:
                self.root.after(
                    0, self._log,
                    f"[{index}/{total}] Continuing with low-confidence match ({score}%): {label}",
                )
                break
            else:  # "skip", or "continue" with nothing to continue with
                self.root.after(0, self._log, f"[{index}/{total}] Skipped: {label}")
                log_history_entry({
                    "source": "spotify", "url": "", "title": label,
                    "status": "skipped",
                    "detail": f"no confident match ({score}%)" if best else "no match found on Spotify or YouTube",
                })
                return

        if self.cancel_event.is_set():
            return

        video_id = best.get("id")
        video_url = f"https://www.youtube.com/watch?v={video_id}" if video_id else best.get("url")
        if not video_url:
            self.root.after(0, self._log, f"[{index}/{total}] Match found but no usable URL: {label}")
            return

        postprocessors = [{"key": "FFmpegMetadata"}]
        if quality == "Best available (no re-encode)":
            postprocessors.insert(0, {"key": "FFmpegExtractAudio", "preferredcodec": "mp3"})
        else:
            kbps = quality.replace(" kbps", "")
            postprocessors.insert(
                0, {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": kbps}
            )
        if self.settings.embed_thumbnail.get():
            postprocessors.append({"key": "EmbedThumbnail"})

        ydl_opts = dict(ydl_opts_base)
        ydl_opts.update({
            "format": "bestaudio/best",
            "outtmpl": out_path_no_ext + ".%(ext)s",
            "postprocessors": postprocessors,
            "writethumbnail": self.settings.embed_thumbnail.get(),
            "quiet": True,
            "no_warnings": True,
        })

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                ydl.download([video_url])
            self.root.after(
                0, self._log,
                f"[{index}/{total}] Done: {label}  (matched: {best.get('title', '?')}, {score}%)",
            )
            log_history_entry({
                "source": "spotify", "url": video_url, "title": label,
                "status": "done", "detail": f"matched YouTube ({score}%): {best.get('title', '?')}",
                "format": "mp3",
            })
        except Exception as e:
            self.root.after(0, self._log, f"[{index}/{total}] Download failed: {label} ({e})")
            log_history_entry({
                "source": "spotify", "url": video_url, "title": label,
                "status": "failed", "detail": str(e),
            })

    def _finish(self, cancelled=False):
        self.is_downloading = False
        self.download_btn.configure(state="normal", text="Download")
        self.import_btn.configure(state="normal", text="Import CSV/ZIP...")
        self.cancel_btn.configure(state="disabled")
        self.open_folder_btn.configure(state="normal")
        self.progress_var.set(0)
        self.progress_bar.set(0)
        self.status_var.set("Cancelled." if cancelled else "Done. Ready for more.")