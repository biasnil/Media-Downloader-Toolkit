"""The main application window and download orchestration logic."""

import os
import time
import threading
import concurrent.futures
import tkinter as tk
from tkinter import messagebox, filedialog

import customtkinter as ctk
import yt_dlp

import Script.theme as theme
from Script.context_menu import add_context_menu
from Config.config import DEFAULT_OUTPUT_DIR, load_config, save_config
from Script.history import log_history_entry
from Script.history_window import open_history_window
from Script.playlist_dialog import confirm_playlist, confirm_retry_skip_stop
from Script.utils import (
    check_ffmpeg, get_ffmpeg_location, open_folder, check_existing, is_transient_error,
    parse_rate_limit, url_has_playlist_param, canonical_playlist_url,
    DownloadCancelled, MAX_RETRIES, RETRY_BACKOFF_BASE,
)


class YouTubeTab:
    def __init__(self, master, root, settings):
        self.master = master  # the tab's content frame — widgets pack into this
        self.root = root      # the real top-level window — used for .after() and dialogs
        self.settings = settings  # shared gear-icon settings (thumbnail/subtitles/speed limit)

        config = load_config()

        self.output_dir = tk.StringVar(value=config.get("last_folder", DEFAULT_OUTPUT_DIR))
        self.separate_folders = tk.BooleanVar(value=config.get("separate_folders", False))
        self.audio_output_dir = tk.StringVar(
            value=config.get("last_audio_folder", DEFAULT_OUTPUT_DIR)
        )
        self.video_output_dir = tk.StringVar(
            value=config.get("last_video_folder", DEFAULT_OUTPUT_DIR)
        )
        self.skip_existing = tk.BooleanVar(value=True)
        self.file_format = tk.StringVar(value=config.get("last_format", "mp3"))
        self.quality = tk.StringVar(value=config.get("last_quality", "Best available (no re-encode)"))
        self.cookies_file = tk.StringVar(value=config.get("cookies_file", ""))
        self.is_downloading = False
        self.last_output_dir_used = self.output_dir.get()

        self.cancel_event = threading.Event()
        self.progress_var = tk.DoubleVar(value=0)  # 0-100; converted to 0-1 for CTkProgressBar

        self.MP3_QUALITY_OPTIONS = [
            "Best available (no re-encode)",
            "320 kbps",
            "256 kbps",
            "192 kbps",
            "128 kbps",
        ]
        self.MP4_QUALITY_OPTIONS = [
            "Best available",
            "1080p",
            "720p",
            "480p",
            "360p",
        ]

        self._build_ui()

        if not check_ffmpeg():
            messagebox.showwarning(
                "ffmpeg not found",
                "ffmpeg isn't installed, isn't on PATH, and isn't bundled next to this app.\n"
                "Install it (e.g. 'brew install ffmpeg' or 'sudo apt install ffmpeg'), or place "
                "an ffmpeg executable in the same folder as this program, or conversion to "
                "mp3/mp4 will fail.",
            )

    # ------------------------------------------------------------------ UI

    def _section_label(self, master, text):
        ctk.CTkLabel(
            master, text=text, text_color=theme.TEXT, font=ctk.CTkFont(size=13, weight="bold"),
            anchor="w",
        ).pack(anchor="w", padx=16, pady=(10, 2))

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

    def _build_ui(self):
        pad = {"padx": 16, "pady": 6}

        body = ctk.CTkFrame(self.master, fg_color=theme.BG, corner_radius=0)
        body.pack(fill="both", expand=True)

        self._section_label(body, "YouTube URL(s), one per line")
        self.url_box = ctk.CTkTextbox(
            body, height=110, fg_color=theme.SURFACE, text_color=theme.TEXT,
            border_width=1, border_color=theme.BORDER, scrollbar_button_color=theme.RED,
            scrollbar_button_hover_color=theme.RED_HOVER,
        )
        self.url_box.pack(fill="both", expand=False, padx=16, pady=(0, 6))
        add_context_menu(self.url_box)

        separate_frame = ctk.CTkFrame(body, fg_color="transparent")
        separate_frame.pack(fill="x", **pad)
        ctk.CTkCheckBox(
            separate_frame, text="Use separate folders for audio and video",
            variable=self.separate_folders, command=self._render_folder_widgets,
            fg_color=theme.RED, hover_color=theme.RED_HOVER, checkmark_color=theme.WHITE,
            text_color=theme.TEXT,
        ).pack(side="left")

        self.folder_container = ctk.CTkFrame(body, fg_color="transparent")
        self.folder_container.pack(fill="x")
        self._render_folder_widgets()

        cookies_frame = ctk.CTkFrame(body, fg_color="transparent")
        cookies_frame.pack(fill="x", **pad)
        ctk.CTkLabel(
            cookies_frame, text="Cookies file (optional):", width=140, anchor="w", text_color=theme.TEXT
        ).pack(side="left")
        cookies_entry = ctk.CTkEntry(
            cookies_frame, textvariable=self.cookies_file, fg_color=theme.SURFACE,
            text_color=theme.TEXT, border_color=theme.BORDER,
        )
        cookies_entry.pack(side="left", fill="x", expand=True, padx=6)
        add_context_menu(cookies_entry)
        self._secondary_button(cookies_frame, "Browse...", self._choose_cookies_file).pack(side="left")
        ctk.CTkLabel(
            body,
            text="Only used automatically as a fallback if YouTube blocks a download for rate-limiting/bot checks.",
            text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), wraplength=640, justify="left", anchor="w",
        ).pack(anchor="w", padx=16)

        format_frame = ctk.CTkFrame(body, fg_color="transparent")
        format_frame.pack(fill="x", **pad)

        ctk.CTkLabel(format_frame, text="Format:", text_color=theme.TEXT).pack(side="left")
        ctk.CTkOptionMenu(
            format_frame, variable=self.file_format, values=["mp3", "mp4"],
            command=self._on_format_change, fg_color=theme.SURFACE, button_color=theme.RED,
            button_hover_color=theme.RED_HOVER, text_color=theme.TEXT,
            dropdown_fg_color=theme.SURFACE, dropdown_text_color=theme.TEXT,
            dropdown_hover_color=theme.SURFACE_HOVER,
        ).pack(side="left", padx=(6, 20))

        ctk.CTkLabel(format_frame, text="Quality:", text_color=theme.TEXT).pack(side="left")
        self.quality_menu = ctk.CTkOptionMenu(
            format_frame, variable=self.quality, values=self.MP3_QUALITY_OPTIONS,
            fg_color=theme.SURFACE, button_color=theme.RED, button_hover_color=theme.RED_HOVER,
            text_color=theme.TEXT, dropdown_fg_color=theme.SURFACE, dropdown_text_color=theme.TEXT,
            dropdown_hover_color=theme.SURFACE_HOVER, width=200,
        )
        self.quality_menu.pack(side="left", padx=6)
        self._refresh_quality_options()

        options_frame = ctk.CTkFrame(body, fg_color="transparent")
        options_frame.pack(fill="x", **pad)
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
        self.progress_bar = ctk.CTkProgressBar(
            progress_frame, progress_color=theme.RED, fg_color=theme.SURFACE,
        )
        self.progress_bar.set(0)
        self.progress_bar.pack(fill="x")

        self.status_var = tk.StringVar(value="Idle.")
        ctk.CTkLabel(
            body, textvariable=self.status_var, text_color=theme.TEXT_MUTED,
            anchor="w", justify="left",
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

    def _persist_current_settings(self):
        save_config({
            "last_folder": self.output_dir.get().strip(),
            "last_audio_folder": self.audio_output_dir.get().strip(),
            "last_video_folder": self.video_output_dir.get().strip(),
            "separate_folders": self.separate_folders.get(),
            "last_format": self.file_format.get(),
            "last_quality": self.quality.get(),
            "cookies_file": self.cookies_file.get().strip(),
        })

    def _refresh_quality_options(self):
        options = (
            self.MP3_QUALITY_OPTIONS if self.file_format.get() == "mp3" else self.MP4_QUALITY_OPTIONS
        )
        self.quality_menu.configure(values=options)
        if self.quality.get() not in options:
            self.quality.set(options[0])

    def _on_format_change(self, _value=None):
        self._refresh_quality_options()

    def _choose_cookies_file(self):
        path = filedialog.askopenfilename(
            title="Select cookies.txt",
            filetypes=[("Cookie/text files", "*.txt"), ("All files", "*.*")],
        )
        if path:
            self.cookies_file.set(path)

    def _choose_folder(self, target_var=None):
        folder = filedialog.askdirectory()
        if folder:
            (target_var or self.output_dir).set(folder)

    def _render_folder_widgets(self):
        """Rebuilds the save-location row(s): a single 'Save to' field, or separate
        'Audio save to' / 'Video save to' fields when the checkbox is ticked."""
        for w in self.folder_container.winfo_children():
            w.destroy()

        pad = {"padx": 16, "pady": 6}

        def build_row(label_text, var):
            row = ctk.CTkFrame(self.folder_container, fg_color="transparent")
            row.pack(fill="x", **pad)
            ctk.CTkLabel(row, text=label_text, width=110, anchor="w", text_color=theme.TEXT).pack(side="left")
            entry = ctk.CTkEntry(
                row, textvariable=var, fg_color=theme.SURFACE, text_color=theme.TEXT,
                border_color=theme.BORDER,
            )
            entry.pack(side="left", fill="x", expand=True, padx=6)
            add_context_menu(entry)
            self._secondary_button(row, "Browse...", lambda: self._choose_folder(var)).pack(side="left")

        if self.separate_folders.get():
            build_row("Audio save to:", self.audio_output_dir)
            build_row("Video save to:", self.video_output_dir)
        else:
            build_row("Save to:", self.output_dir)

    def _open_output_folder(self):
        open_folder(self.last_output_dir_used)

    def _log(self, msg):
        self.log_box.configure(state="normal")
        self.log_box.insert("end", msg + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    # --------------------------------------------------------------- Download

    def _start_download(self):
        if self.is_downloading:
            return

        urls = [u.strip() for u in self.url_box.get("1.0", "end").splitlines() if u.strip()]
        if not urls:
            messagebox.showinfo("No URLs", "Paste at least one YouTube URL first.")
            return

        if self.separate_folders.get():
            if self.file_format.get() == "mp3":
                out_dir = self.audio_output_dir.get().strip() or DEFAULT_OUTPUT_DIR
            else:
                out_dir = self.video_output_dir.get().strip() or DEFAULT_OUTPUT_DIR
        else:
            out_dir = self.output_dir.get().strip() or DEFAULT_OUTPUT_DIR
        os.makedirs(out_dir, exist_ok=True)

        self._persist_current_settings()
        self.last_output_dir_used = out_dir

        self.cancel_event.clear()
        self.progress_var.set(0)
        self.progress_bar.set(0)
        self.active_status = {}    # url-batch index -> its current status line
        self.completed_urls = 0
        self.is_downloading = True
        self.open_folder_btn.configure(state="disabled")
        self.download_btn.configure(state="disabled", text="Downloading...")
        self.cancel_btn.configure(state="normal")
        self.status_var.set(f"Checking {len(urls)} link(s)...")

        thread = threading.Thread(target=self._run_downloads, args=(urls, out_dir), daemon=True)
        thread.start()

    def _cancel_download(self):
        if not self.is_downloading:
            return
        self.cancel_event.set()
        self.cancel_btn.configure(state="disabled")
        self.status_var.set("Cancelling... (finishing current step)")

    def _update_progress(self, percent, speed, eta, index, total, playlist_info=None):
        percent = max(0, min(100, percent))
        if playlist_info:
            cur, tot = playlist_info
            prefix = f"[{index}/{total}] Video {cur}/{tot}"
            key = (index, cur)
        else:
            prefix = f"[{index}/{total}]"
            key = (index, None)
        txt = f"{prefix} {percent:.0f}%"
        if speed:
            txt += f" · {speed}"
        if eta:
            txt += f" · ETA {eta}"
        self.active_status[key] = txt
        self._refresh_status_text()

    def _refresh_status_text(self):
        lines = [
            self.active_status[k]
            for k in sorted(self.active_status.keys(), key=lambda k: (k[0], k[1] if k[1] is not None else -1))
        ]
        self.status_var.set("\n".join(lines) if lines else "Idle.")

    def _on_url_finished(self, index, total):
        """Called once (on the main thread) when one pasted link's entire
        download -- including every item if it was a playlist -- is done,
        failed, skipped, or cancelled. Drops its line(s) from the status area
        and advances the overall progress bar, which tracks completed links
        rather than any single link's byte progress now that several can
        be running at once."""
        for key in [k for k in self.active_status if k[0] == index]:
            self.active_status.pop(key, None)
        self._refresh_status_text()
        self.completed_urls += 1
        fraction = self.completed_urls / total if total else 0
        self.progress_var.set(fraction * 100)
        self.progress_bar.set(fraction)

    def _on_playlist_item_finished(self, index, item_idx):
        """Like _on_url_finished, but for a single item within a playlist --
        drops just that item's status line, since the rest of the playlist
        (and the outer link's own line) may still be in progress."""
        self.active_status.pop((index, item_idx), None)
        self._refresh_status_text()

    def _run_downloads(self, urls, out_dir):
        total = len(urls)

        try:
            max_workers = int(self.settings.max_concurrent_downloads.get())
        except (ValueError, AttributeError):
            max_workers = 1
        max_workers = max(1, min(max_workers, total, 5))

        def worker(i, url):
            if self.cancel_event.is_set():
                self.root.after(0, self._on_url_finished, i, total)
                return
            try:
                self._download_one(url, out_dir, i, total)
            except DownloadCancelled:
                pass
            finally:
                self.root.after(0, self._on_url_finished, i, total)

        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = [executor.submit(worker, i, url) for i, url in enumerate(urls, 1)]
            concurrent.futures.wait(futures)

        cancelled = self.cancel_event.is_set()
        if cancelled:
            self.root.after(0, self._log, "Cancelled by user.")
            log_history_entry({"source": "youtube", "url": "", "title": None, "status": "cancelled", "detail": None})

        self.root.after(0, self._finish, cancelled)

    def _build_format_opts(self):
        """Returns (format_selector, postprocessors, target_ext) based on user's format/quality choice."""
        fmt = self.file_format.get()
        quality = self.quality.get()

        if fmt == "mp3":
            postprocessors = [{"key": "FFmpegMetadata"}]
            if quality == "Best available (no re-encode)":
                postprocessors.insert(0, {"key": "FFmpegExtractAudio", "preferredcodec": "mp3"})
            else:
                kbps = quality.replace(" kbps", "")
                postprocessors.insert(
                    0,
                    {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": kbps},
                )
            return "bestaudio/best", postprocessors, "mp3"

        else:  # mp4
            postprocessors = [{"key": "FFmpegMetadata"}]
            res_map = {
                "Best available": None,
                "1080p": 1080,
                "720p": 720,
                "480p": 480,
                "360p": 360,
            }
            height = res_map.get(quality)
            if height:
                fmt_selector = f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]/best[height<={height}]"
            else:
                fmt_selector = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
            return fmt_selector, postprocessors, "mp4"

    BOT_CHECK_MARKERS = (
        "sign in to confirm",
        "not a bot",
        "429",
        "too many requests",
    )

    def _download_one(self, url, out_dir, index, total):
        try:
            self._attempt_download(url, out_dir, index, total, use_cookies=False)
        except DownloadCancelled:
            raise
        except yt_dlp.utils.DownloadError as e:
            msg = str(e).lower()
            is_bot_check = any(marker in msg for marker in self.BOT_CHECK_MARKERS)
            cookies_path = self.cookies_file.get().strip()

            if is_bot_check and cookies_path and os.path.isfile(cookies_path):
                self.root.after(
                    0,
                    self._log,
                    f"[{index}/{total}] Blocked (rate-limit/bot check) — retrying with cookies file...",
                )
                try:
                    self._attempt_download(url, out_dir, index, total, use_cookies=True)
                except DownloadCancelled:
                    raise
                except yt_dlp.utils.DownloadError as e2:
                    self.root.after(0, self._log, f"[{index}/{total}] Failed even with cookies: {e2}")
                    log_history_entry({"source": "youtube", "url": url, "title": None, "status": "failed", "detail": str(e2)})
                except Exception as e2:
                    self.root.after(0, self._log, f"[{index}/{total}] Unexpected error: {e2}")
                    log_history_entry({"source": "youtube", "url": url, "title": None, "status": "failed", "detail": str(e2)})
            else:
                self.root.after(0, self._log, f"[{index}/{total}] Failed: {e}")
                log_history_entry({"source": "youtube", "url": url, "title": None, "status": "failed", "detail": str(e)})
        except Exception as e:
            self.root.after(0, self._log, f"[{index}/{total}] Unexpected error: {e}")
            log_history_entry({"source": "youtube", "url": url, "title": None, "status": "failed", "detail": str(e)})

    def _attempt_download(self, url, out_dir, index, total, use_cookies):
        skip = self.skip_existing.get()

        def hook(d):
            if self.cancel_event.is_set():
                raise DownloadCancelled()
            if d["status"] == "downloading":
                downloaded = d.get("downloaded_bytes") or 0
                total_bytes = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                percent = (downloaded / total_bytes * 100) if total_bytes else 0
                speed = (d.get("_speed_str") or "").strip()
                eta = (d.get("_eta_str") or "").strip()
                self.root.after(0, self._update_progress, percent, speed, eta, index, total)
            elif d["status"] == "finished":
                self.root.after(0, self._update_progress, 100, "", "", index, total)

        format_selector, postprocessors, target_ext = self._build_format_opts()

        ydl_opts = {
            "format": format_selector,
            "outtmpl": f"{out_dir}/%(title)s.%(ext)s",
            "postprocessors": postprocessors,
            "progress_hooks": [hook],
            "quiet": True,
            "no_warnings": True,
            "restrictfilenames": False,  # keep readable filenames like "Artist - Song.mp3"
        }
        if target_ext == "mp4":
            ydl_opts["merge_output_format"] = "mp4"
        if use_cookies:
            ydl_opts["cookiefile"] = self.cookies_file.get().strip()
        ffmpeg_location = get_ffmpeg_location()
        if ffmpeg_location:
            ydl_opts["ffmpeg_location"] = ffmpeg_location

        if self.settings.embed_thumbnail.get():
            ydl_opts["writethumbnail"] = True
            postprocessors.append({"key": "EmbedThumbnail"})

        if self.settings.download_subtitles.get() and target_ext == "mp4":
            langs = [s.strip() for s in self.settings.subtitle_langs.get().split(",") if s.strip()]
            ydl_opts["writesubtitles"] = True
            ydl_opts["subtitleslangs"] = langs or ["en"]
            postprocessors.append({"key": "FFmpegEmbedSubtitle"})

        rate_limit = parse_rate_limit(self.settings.speed_limit.get())
        if rate_limit:
            ydl_opts["ratelimit"] = rate_limit

        if self.cancel_event.is_set():
            raise DownloadCancelled()

        # A link like 'watch?v=X&list=Y&index=N' points at one video inside
        # a playlist. yt-dlp's own default behavior for that combination has
        # been inconsistent across versions (sometimes just the video,
        # sometimes the whole playlist -- see yt-dlp issues #4269 and
        # #5816), so force playlist mode explicitly whenever a list ID is
        # present, instead of leaving it to that default. Probing the plain
        # playlist URL (rather than the watch+index one) also ensures we see
        # every item from #1 onward, not just from wherever the pasted link
        # happened to point.
        probe_url = url
        if url_has_playlist_param(url):
            ydl_opts["noplaylist"] = False
            probe_url = canonical_playlist_url(url) or url

        # Probe first without downloading, so we can detect playlists / check duplicates
        probe_opts = dict(ydl_opts)
        probe_opts["extract_flat"] = True
        with yt_dlp.YoutubeDL(probe_opts) as probe:
            info = probe.extract_info(probe_url, download=False)

        is_playlist = info.get("_type") == "playlist" or "entries" in info

        if is_playlist:
            entries = list(info.get("entries", []))
            title = info.get("title", "playlist")
            selected_urls = confirm_playlist(self.root, entries, title)
            if self.cancel_event.is_set():
                raise DownloadCancelled()
            if selected_urls is None:
                self.root.after(0, self._log, f"[{index}/{total}] Skipped playlist: {title}")
                log_history_entry({"source": "youtube", "url": url, "title": title, "status": "skipped", "detail": "user cancelled selection"})
                return
            self.root.after(
                0, self._log,
                f"[{index}/{total}] Playlist '{title}': downloading {len(selected_urls)} item(s)",
            )
            self._download_playlist(ydl_opts, selected_urls, title, out_dir, target_ext, index, total)
            return
        else:
            ydl_opts["noplaylist"] = True

        title_for_history = None
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            if not is_playlist:
                full_info = ydl.extract_info(url, download=False)
                title = full_info.get("title", "audio")
                title_for_history = title
                if skip and check_existing(title, out_dir, target_ext):
                    self.root.after(
                        0, self._log, f"[{index}/{total}] Skipped (already exists): {title}"
                    )
                    log_history_entry({"source": "youtube", "url": url, "title": title, "status": "skipped", "detail": "already exists"})
                    return

            attempt = 0
            while True:
                if self.cancel_event.is_set():
                    raise DownloadCancelled()
                try:
                    ydl.download([url])
                    break
                except yt_dlp.utils.DownloadError as e:
                    if is_transient_error(str(e)) and attempt < MAX_RETRIES:
                        attempt += 1
                        wait = RETRY_BACKOFF_BASE ** attempt
                        self.root.after(
                            0, self._log,
                            f"[{index}/{total}] Transient error, retrying in {wait}s "
                            f"(attempt {attempt}/{MAX_RETRIES})...",
                        )
                        time.sleep(wait)
                        continue
                    raise

            self.root.after(0, self._log, f"[{index}/{total}] Done: {url}")
            log_history_entry({
                "source": "youtube",
                "url": url,
                "title": title_for_history,
                "status": "done",
                "detail": None,
                "format": target_ext,
            })

    def _download_playlist(self, ydl_opts, selected_urls, playlist_title, out_dir, target_ext, index, total):
        """Downloads a playlist's items -- up to the same 'at once' setting
        as the outer batch of pasted links -- instead of one at a time.
        A lock serializes the retry/skip/stop dialog so simultaneous
        failures never stack multiple popups; once a choice is remembered,
        every other concurrently-failing item picks it up automatically."""
        skip = self.skip_existing.get()
        total_items = len(selected_urls)
        base_opts = dict(ydl_opts)
        base_opts.pop("playlist_items", None)
        base_opts["noplaylist"] = True

        try:
            max_workers = int(self.settings.max_concurrent_downloads.get())
        except (ValueError, AttributeError):
            max_workers = 1
        max_workers = max(1, min(max_workers, total_items, 5))

        dialog_lock = threading.Lock()
        remembered_action = [None]  # boxed so the nested closure can write to it
        stop_event = threading.Event()

        def process_item(item_idx, item_url):
            if self.cancel_event.is_set() or stop_event.is_set():
                return

            def hook(d, item_idx=item_idx):
                if self.cancel_event.is_set():
                    raise DownloadCancelled()
                if d["status"] == "downloading":
                    downloaded = d.get("downloaded_bytes") or 0
                    total_bytes = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                    percent = (downloaded / total_bytes * 100) if total_bytes else 0
                    speed = (d.get("_speed_str") or "").strip()
                    eta = (d.get("_eta_str") or "").strip()
                    self.root.after(
                        0, self._update_progress, percent, speed, eta, index, total, (item_idx, total_items)
                    )
                elif d["status"] == "finished":
                    self.root.after(
                        0, self._update_progress, 100, "", "", index, total, (item_idx, total_items)
                    )

            item_opts = dict(base_opts)
            item_opts["progress_hooks"] = [hook]
            label_prefix = f"[{index}/{total}] Video {item_idx}/{total_items}"

            try:
                with yt_dlp.YoutubeDL(item_opts) as probe_ydl:
                    item_info = probe_ydl.extract_info(item_url, download=False)
                item_title = item_info.get("title") or item_url
            except Exception:
                item_title = item_url

            if skip and check_existing(item_title, out_dir, target_ext):
                self.root.after(0, self._log, f"{label_prefix} skipped (already exists): {item_title}")
                log_history_entry({
                    "source": "youtube", "url": item_url, "title": item_title,
                    "status": "skipped", "detail": "already exists",
                })
                self.root.after(0, self._on_playlist_item_finished, index, item_idx)
                return

            attempt = 0
            while True:
                if self.cancel_event.is_set():
                    self.root.after(0, self._on_playlist_item_finished, index, item_idx)
                    return
                if stop_event.is_set():
                    self.root.after(0, self._on_playlist_item_finished, index, item_idx)
                    return
                try:
                    with yt_dlp.YoutubeDL(item_opts) as ydl:
                        ydl.download([item_url])
                    self.root.after(0, self._log, f"{label_prefix} done: {item_title}")
                    log_history_entry({
                        "source": "youtube", "url": item_url, "title": item_title,
                        "status": "done", "detail": None, "format": target_ext,
                    })
                    break
                except DownloadCancelled:
                    self.root.after(0, self._on_playlist_item_finished, index, item_idx)
                    return
                except yt_dlp.utils.DownloadError as e:
                    if is_transient_error(str(e)) and attempt < MAX_RETRIES:
                        attempt += 1
                        wait = RETRY_BACKOFF_BASE ** attempt
                        self.root.after(
                            0, self._log,
                            f"{label_prefix} transient error, retrying in {wait}s "
                            f"(attempt {attempt}/{MAX_RETRIES})...",
                        )
                        time.sleep(wait)
                        continue

                    # Not transient (or automatic retries exhausted) -- let the
                    # user decide instead of aborting the whole playlist. The
                    # lock means only one dialog is ever on screen at once,
                    # even if several items hit this at the same moment --
                    # everyone else waits, then picks up whatever was chosen
                    # if it was remembered.
                    with dialog_lock:
                        if remembered_action[0] is not None:
                            action = remembered_action[0]
                        else:
                            action, remember = confirm_retry_skip_stop(
                                self.root, f"{label_prefix}: {item_title}", str(e)
                            )
                            if remember and action in ("skip", "stop"):
                                remembered_action[0] = action
                                self.root.after(
                                    0, self._log,
                                    f"[{index}/{total}] Remembering '{action}' for the rest of "
                                    f"this playlist.",
                                )

                    if action == "retry":
                        attempt = 0
                        continue
                    elif action == "skip":
                        self.root.after(0, self._log, f"{label_prefix} skipped after error: {e}")
                        log_history_entry({
                            "source": "youtube", "url": item_url, "title": item_title,
                            "status": "failed", "detail": str(e),
                        })
                        break
                    else:  # "stop"
                        stop_event.set()
                        self.root.after(
                            0, self._log,
                            f"[{index}/{total}] Playlist '{playlist_title}' stopped by user "
                            f"at video {item_idx}/{total_items}",
                        )
                        log_history_entry({
                            "source": "youtube", "url": item_url, "title": item_title,
                            "status": "failed", "detail": "stopped by user",
                        })
                        break
                except Exception as e:
                    self.root.after(0, self._log, f"{label_prefix} unexpected error: {e}")
                    log_history_entry({
                        "source": "youtube", "url": item_url, "title": item_title,
                        "status": "failed", "detail": str(e),
                    })
                    break

            self.root.after(0, self._on_playlist_item_finished, index, item_idx)

        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pl_executor:
            futures = [
                pl_executor.submit(process_item, item_idx, item_url)
                for item_idx, item_url in enumerate(selected_urls, 1)
            ]
            concurrent.futures.wait(futures)

    def _finish(self, cancelled=False):
        self.is_downloading = False
        self.active_status = {}
        self.download_btn.configure(state="normal", text="Download")
        self.cancel_btn.configure(state="disabled")
        self.open_folder_btn.configure(state="normal")
        self.progress_var.set(0)
        self.progress_bar.set(0)
        self.status_var.set("Cancelled." if cancelled else "Done. Ready for more.")