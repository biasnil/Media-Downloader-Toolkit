"""The 'Other Sites' download tab — any link yt-dlp recognizes, not just
YouTube. Instagram and X are the two this was originally built for, but the
same code works for Reddit, TikTok, Facebook, Vimeo, Twitch, SoundCloud,
Tumblr, Bilibili, Threads, and hundreds of other sites yt-dlp supports."""

import os
import time
import threading
import tkinter as tk
from tkinter import messagebox, filedialog

import customtkinter as ctk
import yt_dlp

import Script.theme as theme
from Script.context_menu import add_context_menu
from Config.insta_config import DEFAULT_SAVE_FOLDER, load_config, save_config
from Script.history import log_history_entry
from Script.history_window import open_history_window
from Script.utils import (
    check_ffmpeg, get_ffmpeg_location, open_folder, is_transient_error,
    parse_rate_limit, DownloadCancelled, MAX_RETRIES, RETRY_BACKOFF_BASE,
)

# Sites known to work well here. This is just used for a friendlier heads-up
# message on unrecognized links -- it's not a hard allow-list. yt-dlp itself
# supports 1,800+ sites, so pasting a link this list doesn't recognize will
# still be attempted; it'll simply fail with yt-dlp's own error if the site
# genuinely isn't supported.
KNOWN_DOMAINS = (
    "instagram.com", "x.com", "twitter.com", "reddit.com", "redd.it",
    "tiktok.com", "facebook.com", "fb.watch", "vimeo.com", "dailymotion.com",
    "soundcloud.com", "twitch.tv", "tumblr.com", "bilibili.com", "threads.net",
)


class OtherSitesTab:
    def __init__(self, master, root, settings):
        self.master = master
        self.root = root
        self.settings = settings  # shared gear-icon settings (thumbnail/subtitles/speed limit)

        config = load_config()
        self.save_folder = tk.StringVar(value=config.get("save_folder", DEFAULT_SAVE_FOLDER))
        self.mode = tk.StringVar(value=config.get("mode", "video"))  # "video" or "audio"

        self.is_downloading = False
        self.cancel_event = threading.Event()
        self.progress_var = tk.DoubleVar(value=0)

        self._build_ui()

        if not check_ffmpeg():
            messagebox.showwarning(
                "ffmpeg not found",
                "ffmpeg isn't installed, isn't on PATH, and isn't bundled next to this app.\n"
                "Install it, or place an ffmpeg executable in the same folder as this program, "
                "or audio extraction / video muxing will fail.",
            )

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

        self._section_label(body, "Link (Instagram, X, Reddit, TikTok, and more)")
        self.url_entry = ctk.CTkEntry(
            body, fg_color=theme.SURFACE, text_color=theme.TEXT, border_color=theme.BORDER,
        )
        self.url_entry.pack(fill="x", padx=16, pady=(0, 6))
        add_context_menu(self.url_entry)

        mode_frame = ctk.CTkFrame(body, fg_color=theme.SURFACE, border_width=1, border_color=theme.BORDER)
        mode_frame.pack(fill="x", **pad)
        ctk.CTkLabel(
            mode_frame, text="Download as", text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11),
        ).pack(anchor="w", padx=12, pady=(6, 0))
        radio_row = ctk.CTkFrame(mode_frame, fg_color="transparent")
        radio_row.pack(fill="x", padx=6, pady=(0, 6))
        ctk.CTkRadioButton(
            radio_row, text="Video (mp4)", variable=self.mode, value="video",
            fg_color=theme.RED, hover_color=theme.RED_HOVER, text_color=theme.TEXT,
        ).pack(side="left", padx=10, pady=6)
        ctk.CTkRadioButton(
            radio_row, text="Audio only (mp3, 320kbps)", variable=self.mode, value="audio",
            fg_color=theme.RED, hover_color=theme.RED_HOVER, text_color=theme.TEXT,
        ).pack(side="left", padx=10, pady=6)

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

        btn_frame = ctk.CTkFrame(body, fg_color="transparent")
        btn_frame.pack(pady=10)
        self.download_btn = self._primary_button(btn_frame, "Download", self._start_download)
        self.download_btn.pack(side="left", padx=4)
        self.cancel_btn = self._secondary_button(
            btn_frame, "Cancel", self._cancel_download, state="disabled"
        )
        self.cancel_btn.pack(side="left", padx=4)
        self._secondary_button(btn_frame, "Open Folder", self._open_output_folder).pack(side="left", padx=4)
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
            body, textvariable=self.status_var, text_color=theme.TEXT_MUTED, anchor="w"
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
        folder = self.save_folder.get().strip()
        if folder and os.path.isdir(folder):
            open_folder(folder)
        else:
            messagebox.showerror("Error", "Folder does not exist.")

    # --------------------------------------------------------------- Download

    def _start_download(self):
        if self.is_downloading:
            return

        url = self.url_entry.get().strip()
        if not url:
            messagebox.showwarning("Missing link", "Please paste a link first.")
            return
        if not any(d in url for d in KNOWN_DOMAINS):
            if not messagebox.askyesno(
                "Unrecognized link",
                "This isn't one of the sites this tab is tested against, but yt-dlp "
                "supports 1,800+ sites total, so it may still work. Try anyway?",
            ):
                return

        folder = self.save_folder.get().strip()
        if not folder:
            messagebox.showwarning("Missing folder", "Please choose a save folder.")
            return
        os.makedirs(folder, exist_ok=True)

        save_config({"save_folder": folder, "mode": self.mode.get()})

        self.cancel_event.clear()
        self.progress_var.set(0)
        self.progress_bar.set(0)
        self.is_downloading = True
        self.download_btn.configure(state="disabled", text="Downloading...")
        self.cancel_btn.configure(state="normal")
        self.status_var.set("Starting...")

        thread = threading.Thread(
            target=self._download_worker, args=(url, folder, self.mode.get()), daemon=True
        )
        thread.start()

    def _cancel_download(self):
        if not self.is_downloading:
            return
        self.cancel_event.set()
        self.cancel_btn.configure(state="disabled")
        self.status_var.set("Cancelling... (finishing current step)")

    def _update_progress(self, percent, speed, eta):
        percent = max(0, min(100, percent))
        self.progress_var.set(percent)
        self.progress_bar.set(percent / 100)
        txt = f"{percent:.0f}%"
        if speed:
            txt += f" · {speed / 1024 / 1024:.2f} MB/s"
        if eta:
            txt += f" · ETA {eta}s"
        self.status_var.set(txt)

    def _progress_hook(self, d):
        if self.cancel_event.is_set():
            raise DownloadCancelled()
        if d["status"] == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            downloaded = d.get("downloaded_bytes", 0)
            if total:
                pct = downloaded / total * 100
                self.root.after(0, self._update_progress, pct, d.get("speed"), d.get("eta"))
        elif d["status"] == "finished":
            self.root.after(0, self._update_progress, 100, None, None)

    def _download_worker(self, url, folder, mode):
        outtmpl = os.path.join(folder, "%(uploader)s - %(title).80s.%(ext)s")

        ydl_opts = {
            "outtmpl": outtmpl,
            "progress_hooks": [self._progress_hook],
            "quiet": True,
            "no_warnings": True,
            "restrictfilenames": False,
        }
        ffmpeg_location = get_ffmpeg_location()
        if ffmpeg_location:
            ydl_opts["ffmpeg_location"] = ffmpeg_location

        if mode == "audio":
            ydl_opts.update({
                "format": "bestaudio/best",
                "postprocessors": [
                    {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "320"}
                ],
            })
        else:
            ydl_opts.update({
                "format": "bestvideo+bestaudio/best",
                "merge_output_format": "mp4",
                "postprocessors": [],
            })

        if self.settings.embed_thumbnail.get():
            ydl_opts["writethumbnail"] = True
            ydl_opts["postprocessors"].append({"key": "EmbedThumbnail"})

        if self.settings.download_subtitles.get() and mode == "video":
            langs = [s.strip() for s in self.settings.subtitle_langs.get().split(",") if s.strip()]
            ydl_opts["writesubtitles"] = True
            ydl_opts["subtitleslangs"] = langs or ["en"]
            ydl_opts["postprocessors"].append({"key": "FFmpegEmbedSubtitle"})

        rate_limit = parse_rate_limit(self.settings.speed_limit.get())
        if rate_limit:
            ydl_opts["ratelimit"] = rate_limit

        title_for_history = None
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                if self.cancel_event.is_set():
                    raise DownloadCancelled()
                info = ydl.extract_info(url, download=False)
                title_for_history = info.get("title")

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
                                f"Transient error, retrying in {wait}s (attempt {attempt}/{MAX_RETRIES})...",
                            )
                            time.sleep(wait)
                            continue
                        raise

            self.root.after(0, self._log, f"Done: {url}")
            log_history_entry({
                "source": "other_sites", "url": url, "title": title_for_history,
                "status": "done", "detail": None, "format": mode,
            })
            self.root.after(0, self._download_done, True, None)
        except DownloadCancelled:
            self.root.after(0, self._log, "Cancelled by user.")
            log_history_entry({
                "source": "other_sites", "url": url, "title": title_for_history,
                "status": "cancelled", "detail": None,
            })
            self.root.after(0, self._download_done, False, "Cancelled")
        except Exception as e:
            self.root.after(0, self._log, f"Failed: {e}")
            log_history_entry({
                "source": "other_sites", "url": url, "title": title_for_history,
                "status": "failed", "detail": str(e),
            })
            self.root.after(0, self._download_done, False, str(e))

    def _download_done(self, success, error):
        self.is_downloading = False
        self.download_btn.configure(state="normal", text="Download")
        self.cancel_btn.configure(state="disabled")
        self.progress_var.set(0)
        self.progress_bar.set(0)
        if success:
            self.status_var.set("Done!")
        else:
            self.status_var.set(f"Failed: {error}" if error else "Failed")
            if error and error != "Cancelled":
                messagebox.showerror("Download failed", error)