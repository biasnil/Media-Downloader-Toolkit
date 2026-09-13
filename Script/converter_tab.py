"""The Video/Audio Format Converter tab — converts local files between
formats using ffmpeg directly (no downloading involved)."""

import os
import shutil
import threading
import subprocess
import tkinter as tk
from tkinter import messagebox, filedialog

import customtkinter as ctk

import Script.theme as theme
from Script.context_menu import add_context_menu
from Script.history import log_history_entry
from Script.utils import get_ffmpeg_location, open_folder

AUDIO_FORMATS = ["mp3", "wav", "flac", "aac", "m4a", "ogg"]
VIDEO_FORMATS = ["mp4", "mkv", "mov", "avi", "webm"]
ALL_FORMATS = AUDIO_FORMATS + VIDEO_FORMATS


class DownloadCancelledLocal(Exception):
    """Raised to abort an in-progress ffmpeg conversion when the user cancels."""
    pass


def _ffmpeg_binary():
    return get_ffmpeg_location() or shutil.which("ffmpeg") or "ffmpeg"


class ConverterTab:
    def __init__(self, master, root):
        self.master = master
        self.root = root

        self.files = []  # list of source file paths
        self.target_format = tk.StringVar(value="mp3")
        self.is_converting = False
        self.cancel_event = threading.Event()
        self.current_process = None
        self.progress_var = tk.DoubleVar(value=0)
        self._last_output_dir = None

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

        top = ctk.CTkFrame(body, fg_color="transparent")
        top.pack(fill="x", **pad)
        self._secondary_button(top, "Add files...", self._add_files).pack(side="left")
        self._secondary_button(top, "Clear", self._clear_files).pack(side="left", padx=(6, 0))

        self._section_label(body, "Files to convert")
        self.file_list_frame = ctk.CTkScrollableFrame(
            body, fg_color=theme.SURFACE, scrollbar_button_color=theme.RED,
            scrollbar_button_hover_color=theme.RED_HOVER, height=180,
        )
        self.file_list_frame.pack(fill="both", expand=True, padx=16, pady=(0, 6))
        self._render_file_list()

        format_row = ctk.CTkFrame(body, fg_color="transparent")
        format_row.pack(fill="x", **pad)
        ctk.CTkLabel(format_row, text="Convert to:", text_color=theme.TEXT).pack(side="left")
        ctk.CTkOptionMenu(
            format_row, variable=self.target_format, values=ALL_FORMATS,
            fg_color=theme.SURFACE, button_color=theme.RED, button_hover_color=theme.RED_HOVER,
            text_color=theme.TEXT, dropdown_fg_color=theme.SURFACE, dropdown_text_color=theme.TEXT,
            dropdown_hover_color=theme.SURFACE_HOVER,
        ).pack(side="left", padx=6)
        ctk.CTkLabel(
            format_row,
            text="Converting a video to an audio-only format drops the video stream automatically.",
            text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11),
        ).pack(side="left", padx=10)

        btn_frame = ctk.CTkFrame(body, fg_color="transparent")
        btn_frame.pack(pady=10)
        self.convert_btn = self._primary_button(btn_frame, "Convert", self._start_convert)
        self.convert_btn.pack(side="left", padx=4)
        self.cancel_btn = self._secondary_button(
            btn_frame, "Cancel", self._cancel_convert, state="disabled"
        )
        self.cancel_btn.pack(side="left", padx=4)
        self.open_folder_btn = self._secondary_button(
            btn_frame, "Open Output Folder", self._open_last_output, state="disabled"
        )
        self.open_folder_btn.pack(side="left", padx=4)

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
            scrollbar_button_hover_color=theme.RED_HOVER, height=110,
        )
        self.log_box.configure(state="disabled")
        self.log_box.pack(fill="both", expand=True, padx=16, pady=(0, 16))
        add_context_menu(self.log_box)

    def _render_file_list(self):
        for w in self.file_list_frame.winfo_children():
            w.destroy()
        if not self.files:
            ctk.CTkLabel(
                self.file_list_frame, text="No files added yet.", text_color=theme.TEXT_MUTED,
            ).pack(anchor="w", padx=6, pady=6)
            return
        for path in self.files:
            row = ctk.CTkFrame(self.file_list_frame, fg_color="transparent")
            row.pack(fill="x", anchor="w", pady=1)
            ctk.CTkLabel(row, text=os.path.basename(path), text_color=theme.TEXT, anchor="w").pack(
                side="left", padx=6
            )
            ctk.CTkButton(
                row, text="✕", width=28, command=lambda p=path: self._remove_file(p),
                fg_color=theme.SURFACE, hover_color=theme.SURFACE_HOVER, text_color=theme.RED,
            ).pack(side="right", padx=6)

    def _add_files(self):
        paths = filedialog.askopenfilenames(title="Select audio/video files")
        for p in paths:
            if p not in self.files:
                self.files.append(p)
        self._render_file_list()

    def _remove_file(self, path):
        self.files = [p for p in self.files if p != path]
        self._render_file_list()

    def _clear_files(self):
        self.files = []
        self._render_file_list()

    def _log(self, msg):
        self.log_box.configure(state="normal")
        self.log_box.insert("end", msg + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def _open_last_output(self):
        if self._last_output_dir and os.path.isdir(self._last_output_dir):
            open_folder(self._last_output_dir)

    # --------------------------------------------------------------- Convert

    def _start_convert(self):
        if self.is_converting:
            return
        if not self.files:
            messagebox.showinfo("No files", "Add at least one file to convert first.")
            return

        self.cancel_event.clear()
        self.is_converting = True
        self.progress_var.set(0)
        self.progress_bar.set(0)
        self.convert_btn.configure(state="disabled", text="Converting...")
        self.cancel_btn.configure(state="normal")
        self.status_var.set(f"Converting 0/{len(self.files)}...")
        self._last_output_dir = None

        target_format = self.target_format.get()
        files = list(self.files)
        thread = threading.Thread(target=self._convert_worker, args=(files, target_format), daemon=True)
        thread.start()

    def _cancel_convert(self):
        if not self.is_converting:
            return
        self.cancel_event.set()
        self.cancel_btn.configure(state="disabled")
        self.status_var.set("Cancelling... (finishing current file)")
        if self.current_process is not None:
            try:
                self.current_process.terminate()
            except Exception:
                pass

    def _convert_worker(self, files, target_format):
        ffmpeg_bin = _ffmpeg_binary()
        is_audio_target = target_format in AUDIO_FORMATS
        cancelled = False
        errors = []

        for i, src in enumerate(files, start=1):
            if self.cancel_event.is_set():
                cancelled = True
                break

            out_dir = os.path.join(os.path.dirname(src), "converted")
            os.makedirs(out_dir, exist_ok=True)
            self._last_output_dir = out_dir
            base_name = os.path.splitext(os.path.basename(src))[0]
            out_path = os.path.join(out_dir, f"{base_name}.{target_format}")

            self.root.after(0, self.status_var.set, f"Converting {i}/{len(files)}: {os.path.basename(src)}")

            cmd = [ffmpeg_bin, "-y", "-i", src]
            if is_audio_target:
                cmd += ["-vn"]  # drop any video stream when converting to an audio-only format
            cmd += [out_path]

            try:
                self.current_process = subprocess.Popen(
                    cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                )
                _, stderr = self.current_process.communicate()
                returncode = self.current_process.returncode
                self.current_process = None

                if self.cancel_event.is_set():
                    cancelled = True
                    break

                if returncode != 0:
                    detail = stderr.decode(errors="ignore")[-500:] if stderr else "ffmpeg failed"
                    errors.append(f"{os.path.basename(src)}: {detail.strip().splitlines()[-1] if detail.strip() else 'ffmpeg failed'}")
                    self.root.after(0, self._log, f"Failed: {os.path.basename(src)}")
                    log_history_entry({
                        "source": "converter", "url": src, "title": os.path.basename(src),
                        "status": "failed", "detail": "ffmpeg conversion failed",
                    })
                else:
                    self.root.after(0, self._log, f"Done: {os.path.basename(src)} -> {out_path}")
                    log_history_entry({
                        "source": "converter", "url": src, "title": os.path.basename(src),
                        "status": "done", "detail": None, "format": target_format,
                    })
            except Exception as e:
                errors.append(f"{os.path.basename(src)}: {e}")
                self.root.after(0, self._log, f"Failed: {os.path.basename(src)} ({e})")
                log_history_entry({
                    "source": "converter", "url": src, "title": os.path.basename(src),
                    "status": "failed", "detail": str(e),
                })

            self.root.after(0, self._update_progress, i / len(files))

        self.root.after(0, self._finish, cancelled, errors)

    def _update_progress(self, fraction):
        self.progress_var.set(fraction)
        self.progress_bar.set(fraction)

    def _finish(self, cancelled, errors):
        self.is_converting = False
        self.convert_btn.configure(state="normal", text="Convert")
        self.cancel_btn.configure(state="disabled")
        self.open_folder_btn.configure(state="normal" if self._last_output_dir else "disabled")
        if cancelled:
            self.status_var.set("Cancelled.")
        elif errors:
            self.status_var.set(f"Done with {len(errors)} error(s).")
            messagebox.showwarning("Finished with errors", "\n".join(errors))
        else:
            self.status_var.set("Done!")