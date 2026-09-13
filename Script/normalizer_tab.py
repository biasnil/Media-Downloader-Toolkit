"""The Music Loudness Normalizer tab."""

import os
import threading
import tkinter as tk
from tkinter import messagebox, filedialog

import customtkinter as ctk

import Script.theme as theme
from Script.context_menu import add_context_menu
from Config.normalizer_config import DEFAULT_TARGET_LUFS, load_config, save_config
from Script.utils import get_ffmpeg_location

try:
    import numpy as np
    from pydub import AudioSegment
    import pyloudnorm as pyln
    AUDIO_IMPORT_ERROR = None
except ImportError as _import_exc:
    AudioSegment = None
    np = None
    pyln = None
    AUDIO_IMPORT_ERROR = str(_import_exc)

AUDIO_EXTENSIONS = {".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".wma"}


def find_audio_files(folder):
    """Return a sorted list of audio file paths directly inside `folder`."""
    files = []
    for name in os.listdir(folder):
        full_path = os.path.join(folder, name)
        if os.path.isfile(full_path):
            ext = os.path.splitext(name)[1].lower()
            if ext in AUDIO_EXTENSIONS:
                files.append(full_path)
    return sorted(files)


def sound_to_float_array(sound):
    """Convert a pydub AudioSegment to a float64 numpy array in [-1, 1],
    shaped (n_samples, n_channels) as pyloudnorm expects."""
    samples = np.array(sound.get_array_of_samples()).astype(np.float64)
    max_val = float(2 ** (8 * sound.sample_width - 1))
    samples /= max_val
    if sound.channels > 1:
        samples = samples.reshape((-1, sound.channels))
    return samples


def match_target_loudness(sound, target_lufs):
    """Return a copy of `sound` with its measured integrated loudness (LUFS)
    shifted to target_lufs. Falls back to simple dBFS matching if the
    measurement fails (e.g. a track that's silence or too short)."""
    try:
        data = sound_to_float_array(sound)
        meter = pyln.Meter(sound.frame_rate)  # ITU-R BS.1770-4 loudness meter
        current_lufs = meter.integrated_loudness(data)
        if current_lufs == float("-inf"):
            raise ValueError("silent or unmeasurable track")
        gain_db = target_lufs - current_lufs
    except Exception:
        gain_db = target_lufs - sound.dBFS
    return sound.apply_gain(gain_db)


class NormalizerTab:
    def __init__(self, master, root):
        self.master = master
        self.root = root

        config = load_config()
        self.folder = config.get("last_folder") or None
        self.target_var = tk.StringVar(value=str(config.get("target_lufs", DEFAULT_TARGET_LUFS)))
        self.file_vars = {}  # path -> tk.BooleanVar

        if AudioSegment is not None:
            bundled_ffmpeg = get_ffmpeg_location()
            if bundled_ffmpeg:
                AudioSegment.converter = bundled_ffmpeg

        self._build_ui()

        if self.folder and os.path.isdir(self.folder):
            self._populate_file_list()

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

    def _build_ui(self):
        pad = {"padx": 16, "pady": 6}
        body = ctk.CTkFrame(self.master, fg_color=theme.BG, corner_radius=0)
        body.pack(fill="both", expand=True)

        top = ctk.CTkFrame(body, fg_color="transparent")
        top.pack(fill="x", **pad)
        self._secondary_button(top, "Browse folder...", self._browse_folder).pack(side="left")
        self.folder_label = ctk.CTkLabel(
            top, text=self.folder or "No folder selected", text_color=theme.TEXT_MUTED,
        )
        self.folder_label.pack(side="left", padx=10)

        list_header = ctk.CTkFrame(body, fg_color="transparent")
        list_header.pack(fill="x", padx=16)
        ctk.CTkLabel(list_header, text="Audio files found:", text_color=theme.TEXT).pack(side="left")
        self._secondary_button(list_header, "Select None", self._select_none).pack(side="right", padx=(4, 0))
        self._secondary_button(list_header, "Select All", self._select_all).pack(side="right")

        self.list_frame = ctk.CTkScrollableFrame(
            body, fg_color=theme.SURFACE, scrollbar_button_color=theme.RED,
            scrollbar_button_hover_color=theme.RED_HOVER,
        )
        self.list_frame.pack(fill="both", expand=True, padx=16, pady=6)

        bottom = ctk.CTkFrame(body, fg_color="transparent")
        bottom.pack(fill="x", **pad)

        target_row = ctk.CTkFrame(bottom, fg_color="transparent")
        target_row.pack(fill="x")
        ctk.CTkLabel(target_row, text="Target loudness (LUFS):", text_color=theme.TEXT).pack(side="left")
        target_entry = ctk.CTkEntry(
            target_row, textvariable=self.target_var, width=80, fg_color=theme.SURFACE,
            text_color=theme.TEXT, border_color=theme.BORDER,
        )
        target_entry.pack(side="left", padx=6)
        add_context_menu(target_entry)
        ctk.CTkLabel(
            target_row, text="(-14 = streaming loudness, -23 = quieter/broadcast standard)",
            text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11),
        ).pack(side="left", padx=6)

        self.normalize_btn = self._primary_button(bottom, "Normalize selected files", self._start_normalize)
        self.normalize_btn.pack(fill="x", pady=(10, 6))

        self.progress_var = tk.DoubleVar(value=0)
        self.progress_bar = ctk.CTkProgressBar(bottom, progress_color=theme.RED, fg_color=theme.SURFACE)
        self.progress_bar.set(0)
        self.progress_bar.pack(fill="x")

        self.status_var = tk.StringVar(value="")
        ctk.CTkLabel(
            bottom, textvariable=self.status_var, text_color=theme.TEXT_MUTED, anchor="w"
        ).pack(fill="x", pady=(6, 0))

    # -------------------------------------------------------------- actions

    def _browse_folder(self):
        folder = filedialog.askdirectory(title="Select your music folder")
        if not folder:
            return
        self.folder = folder
        self.folder_label.configure(text=folder, text_color=theme.TEXT)
        save_config({"last_folder": folder, "target_lufs": self.target_var.get()})
        self._populate_file_list()

    def _populate_file_list(self):
        for widget in self.list_frame.winfo_children():
            widget.destroy()
        self.file_vars.clear()

        files = find_audio_files(self.folder)
        if not files:
            ctk.CTkLabel(
                self.list_frame, text="No audio files found in this folder.", text_color=theme.TEXT_MUTED,
            ).pack(anchor="w", padx=6, pady=6)
            return

        for path in files:
            var = tk.BooleanVar(value=True)
            self.file_vars[path] = var
            ctk.CTkCheckBox(
                self.list_frame, text=os.path.basename(path), variable=var,
                fg_color=theme.RED, hover_color=theme.RED_HOVER, checkmark_color=theme.WHITE,
                text_color=theme.TEXT,
            ).pack(anchor="w", pady=1, padx=6)

    def _select_all(self):
        for var in self.file_vars.values():
            var.set(True)

    def _select_none(self):
        for var in self.file_vars.values():
            var.set(False)

    def _start_normalize(self):
        if AudioSegment is None:
            messagebox.showerror(
                "Missing dependency",
                "Could not import required libraries.\n\n"
                f"Underlying error: {AUDIO_IMPORT_ERROR}\n\n"
                "If the error mentions 'audioop', you're likely on Python 3.13+ "
                "which removed that built-in module. Fix with:\n\n"
                "    pip install audioop-lts\n\n"
                "Otherwise, run:\n\n    pip install pydub pyloudnorm numpy\n\n"
                "and make sure FFmpeg is installed and on your PATH (or bundled next to the app).",
            )
            return

        if not self.folder:
            messagebox.showwarning("No folder", "Choose a folder first.")
            return

        selected = [path for path, var in self.file_vars.items() if var.get()]
        if not selected:
            messagebox.showwarning("Nothing selected", "Select at least one file to normalize.")
            return

        try:
            target_lufs = float(self.target_var.get())
        except (tk.TclError, ValueError):
            messagebox.showerror("Invalid target", "Target loudness must be a number.")
            return

        save_config({"last_folder": self.folder, "target_lufs": target_lufs})

        self.normalize_btn.configure(state="disabled")
        self.progress_var.set(0)
        self.progress_bar.set(0)
        self._total_files = len(selected)
        self.status_var.set("Starting...")

        thread = threading.Thread(target=self._normalize_worker, args=(selected, target_lufs), daemon=True)
        thread.start()

    def _normalize_worker(self, files, target_lufs):
        out_dir = os.path.join(self.folder, "normalized")
        os.makedirs(out_dir, exist_ok=True)

        errors = []
        for i, path in enumerate(files, start=1):
            name = os.path.basename(path)
            self._set_status(f"Processing {name} ({i}/{len(files)})")
            try:
                ext = os.path.splitext(path)[1].lower().lstrip(".")
                sound = AudioSegment.from_file(path)
                normalized = match_target_loudness(sound, target_lufs)
                out_path = os.path.join(out_dir, name)
                export_format = "mp4" if ext in ("m4a", "aac") else ext
                normalized.export(out_path, format=export_format)
            except Exception as exc:  # noqa: BLE001 - report any failure to the user
                errors.append(f"{name}: {exc}")
            self._set_progress(i / len(files))

        if errors:
            self._finish(f"Done with {len(errors)} error(s). See details below.", errors)
        else:
            self._finish(
                f"Done! {len(files)} file(s) normalized to {target_lufs} LUFS.\nSaved in: {out_dir}"
            )

    # --------------------------------------------------- thread-safe UI ops

    def _set_status(self, text):
        self.root.after(0, self.status_var.set, text)

    def _set_progress(self, fraction):
        self.root.after(0, self.progress_var.set, fraction)
        self.root.after(0, self.progress_bar.set, fraction)

    def _finish(self, message, errors=None):
        def _update():
            self.status_var.set(message)
            self.normalize_btn.configure(state="normal")
            if errors:
                messagebox.showwarning("Finished with errors", "\n".join(errors))
            else:
                messagebox.showinfo("Finished", message)

        self.root.after(0, _update)