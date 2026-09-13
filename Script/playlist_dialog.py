"""The playlist selection dialog: a scrollable URL+title checklist plus a
quick numeric range selector (e.g. 1-10, 1-100). Also the retry/skip/stop
dialog shown when one video in a playlist fails to download."""

import threading
import tkinter as tk

import customtkinter as ctk

import Script.theme as theme
from Script.context_menu import add_context_menu


def confirm_playlist(root, entries, title):
    """Must be called from a worker thread; blocks that thread until answered.
    Builds the dialog on the Tk main thread via root.after(). Returns a list
    of the selected videos' URLs (in playlist order), or None if the user
    cancels."""
    result = {"selection": None}
    event = threading.Event()
    count = len(entries)

    def video_url(entry, idx):
        vid = entry.get("id") or entry.get("url") or ""
        if vid and not str(vid).startswith("http"):
            return f"https://www.youtube.com/watch?v={vid}"
        return vid or f"(item {idx})"

    def ask():
        dialog = ctk.CTkToplevel(root)
        dialog.title("Playlist detected")
        dialog.geometry("640x580")
        dialog.configure(fg_color=theme.BG)
        dialog.grab_set()

        pad = {"padx": 16, "pady": 6}

        ctk.CTkLabel(
            dialog,
            text=f'"{title}"  —  {count} videos. Tick the ones you want.',
            justify="left", text_color=theme.TEXT, font=ctk.CTkFont(size=13, weight="bold"),
        ).pack(anchor="w", **pad)

        # Quick range selector
        range_frame = ctk.CTkFrame(dialog, fg_color="transparent")
        range_frame.pack(anchor="w", **pad)
        ctk.CTkLabel(range_frame, text="Quick select #", text_color=theme.TEXT).pack(side="left")
        from_var = tk.StringVar(value="1")
        from_entry = ctk.CTkEntry(
            range_frame, width=56, textvariable=from_var, fg_color=theme.SURFACE,
            text_color=theme.TEXT, border_color=theme.BORDER,
        )
        from_entry.pack(side="left", padx=4)
        add_context_menu(from_entry)
        ctk.CTkLabel(range_frame, text="to #", text_color=theme.TEXT).pack(side="left")
        to_var = tk.StringVar(value=str(count))
        to_entry = ctk.CTkEntry(
            range_frame, width=56, textvariable=to_var, fg_color=theme.SURFACE,
            text_color=theme.TEXT, border_color=theme.BORDER,
        )
        to_entry.pack(side="left", padx=4)
        add_context_menu(to_entry)

        check_vars = [tk.BooleanVar(value=True) for _ in range(count)]

        def apply_range():
            try:
                start, end = int(from_var.get()), int(to_var.get())
            except (ValueError, tk.TclError):
                return
            start, end = max(1, min(start, count)), max(1, min(end, count))
            if start > end:
                start, end = end, start
            for idx in range(count):
                check_vars[idx].set(start <= (idx + 1) <= end)

        def select_all():
            for v in check_vars:
                v.set(True)

        def select_none():
            for v in check_vars:
                v.set(False)

        def secondary_btn(master, text, command):
            return ctk.CTkButton(
                master, text=text, command=command, fg_color=theme.SURFACE,
                hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT,
                border_width=1, border_color=theme.BORDER, width=100,
            )

        ctk.CTkButton(
            range_frame, text="Apply Range", command=apply_range, fg_color=theme.RED,
            hover_color=theme.RED_HOVER, text_color=theme.WHITE, width=110,
        ).pack(side="left", padx=(10, 4))
        secondary_btn(range_frame, "Select All", select_all).pack(side="left", padx=4)
        secondary_btn(range_frame, "Select None", select_none).pack(side="left", padx=4)

        # Scrollable checklist of every entry (URL then title)
        list_frame = ctk.CTkScrollableFrame(
            dialog, fg_color=theme.SURFACE, scrollbar_button_color=theme.RED,
            scrollbar_button_hover_color=theme.RED_HOVER,
        )
        list_frame.pack(fill="both", expand=True, padx=16, pady=6)

        for idx, entry in enumerate(entries):
            row = ctk.CTkFrame(list_frame, fg_color="transparent")
            row.pack(fill="x", anchor="w", pady=1)
            ctk.CTkCheckBox(
                row, text="", variable=check_vars[idx], width=24,
                fg_color=theme.RED, hover_color=theme.RED_HOVER, checkmark_color=theme.WHITE,
            ).pack(side="left")
            text_frame = ctk.CTkFrame(row, fg_color="transparent")
            text_frame.pack(side="left", fill="x", expand=True)
            ctk.CTkLabel(
                text_frame, text=f"{idx + 1}. {video_url(entry, idx + 1)}",
                text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=10),
                anchor="w", justify="left",
            ).pack(anchor="w")
            ctk.CTkLabel(
                text_frame, text=entry.get("title") or "(untitled)",
                text_color=theme.TEXT, anchor="w", justify="left", wraplength=480,
            ).pack(anchor="w")

        error_var = tk.StringVar(value="")
        ctk.CTkLabel(dialog, textvariable=error_var, text_color=theme.RED).pack(anchor="w", padx=16)

        def submit():
            selected = [i + 1 for i, v in enumerate(check_vars) if v.get()]
            if not selected:
                error_var.set("Select at least one video, or Cancel.")
                return
            result["selection"] = [video_url(entries[i - 1], i) for i in selected]
            event.set()
            dialog.destroy()

        def cancel():
            result["selection"] = None
            event.set()
            dialog.destroy()

        btn_frame = ctk.CTkFrame(dialog, fg_color="transparent")
        btn_frame.pack(pady=12)
        ctk.CTkButton(
            btn_frame, text="Download Selected", command=submit, fg_color=theme.RED,
            hover_color=theme.RED_HOVER, text_color=theme.WHITE, font=ctk.CTkFont(weight="bold"),
        ).pack(side="left", padx=6)
        secondary_btn(btn_frame, "Cancel", cancel).pack(side="left", padx=6)

        dialog.protocol("WM_DELETE_WINDOW", cancel)

    root.after(0, ask)
    event.wait()
    return result["selection"]


def confirm_retry_skip_stop(root, item_label, error_message):
    """Blocking dialog shown when a single playlist item fails to download
    (after automatic retries for transient errors are exhausted). Must be
    called from a worker thread. Returns a (action, remember) tuple where
    action is 'retry', 'skip', or 'stop', and remember is True if the user
    wants that choice applied automatically to the rest of this playlist
    (only meaningful for 'skip'/'stop' -- the caller should not persist
    'retry' the same way, since auto-retrying a permanently broken video
    forever would just hang)."""
    result = {"action": "stop", "remember": False}
    event = threading.Event()

    def ask():
        dialog = ctk.CTkToplevel(root)
        dialog.title("Download failed")
        dialog.geometry("480x300")
        dialog.configure(fg_color=theme.BG)
        dialog.grab_set()

        ctk.CTkLabel(
            dialog, text=item_label, text_color=theme.TEXT,
            font=ctk.CTkFont(size=13, weight="bold"), wraplength=440, justify="left",
        ).pack(anchor="w", padx=16, pady=(16, 4))

        ctk.CTkLabel(
            dialog, text=error_message, text_color=theme.TEXT_MUTED,
            font=ctk.CTkFont(size=11), wraplength=440, justify="left",
        ).pack(anchor="w", padx=16, pady=(0, 10))

        ctk.CTkLabel(
            dialog, text="Retry this video, skip it and continue the playlist, "
                         "or stop the playlist here?",
            text_color=theme.TEXT, wraplength=440, justify="left",
        ).pack(anchor="w", padx=16)

        remember_var = tk.BooleanVar(value=False)
        ctk.CTkCheckBox(
            dialog, text="Do this for all remaining videos in this playlist",
            variable=remember_var, fg_color=theme.RED, hover_color=theme.RED_HOVER,
            checkmark_color=theme.WHITE, text_color=theme.TEXT,
        ).pack(anchor="w", padx=16, pady=(10, 0))

        def choose(action):
            result["action"] = action
            result["remember"] = remember_var.get()
            event.set()
            dialog.destroy()

        btn_frame = ctk.CTkFrame(dialog, fg_color="transparent")
        btn_frame.pack(pady=18)
        ctk.CTkButton(
            btn_frame, text="Retry", command=lambda: choose("retry"), fg_color=theme.RED,
            hover_color=theme.RED_HOVER, text_color=theme.WHITE, font=ctk.CTkFont(weight="bold"),
        ).pack(side="left", padx=6)
        ctk.CTkButton(
            btn_frame, text="Skip this video", command=lambda: choose("skip"),
            fg_color=theme.SURFACE, hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT,
            border_width=1, border_color=theme.BORDER,
        ).pack(side="left", padx=6)
        ctk.CTkButton(
            btn_frame, text="Stop playlist", command=lambda: choose("stop"),
            fg_color=theme.SURFACE, hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT,
            border_width=1, border_color=theme.BORDER,
        ).pack(side="left", padx=6)

        dialog.protocol("WM_DELETE_WINDOW", lambda: choose("stop"))

    root.after(0, ask)
    event.wait()
    return result["action"], result["remember"]