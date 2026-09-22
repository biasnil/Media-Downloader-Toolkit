"""Spotify playlist/album track picker. Deliberately a separate dialog from
Script/playlist_dialog.py's YouTube version rather than a shared/modified
one: the data here is Spotify track metadata (title/artist/duration), not
yt-dlp playlist entries (id/url/title), and the two flows differ enough
that forcing them through one dialog wasn't worth it."""

import threading
import tkinter as tk

import customtkinter as ctk

import Script.theme as theme
from Script.context_menu import add_context_menu


def _format_duration(ms):
    if not ms:
        return ""
    total_sec = int(ms / 1000)
    return f"{total_sec // 60}:{total_sec % 60:02d}"


def confirm_spotify_tracks(root, tracks, title):
    """Must be called from a worker thread; blocks that thread until
    answered. Builds the dialog on the Tk main thread via root.after().
    Returns the list of selected track dicts (in playlist order), or None
    if the user cancels."""
    result = {"selection": None}
    event = threading.Event()
    count = len(tracks)

    def ask():
        dialog = ctk.CTkToplevel(root)
        dialog.title("Spotify playlist detected")
        dialog.geometry("640x580")
        dialog.configure(fg_color=theme.BG)
        dialog.grab_set()

        pad = {"padx": 16, "pady": 6}

        ctk.CTkLabel(
            dialog,
            text=f'"{title}"  —  {count} tracks. Tick the ones you want.',
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

        # Scrollable checklist of every track (artist + duration, then title)
        list_frame = ctk.CTkScrollableFrame(
            dialog, fg_color=theme.SURFACE, scrollbar_button_color=theme.RED,
            scrollbar_button_hover_color=theme.RED_HOVER,
        )
        list_frame.pack(fill="both", expand=True, padx=16, pady=6)

        for idx, track in enumerate(tracks):
            row = ctk.CTkFrame(list_frame, fg_color="transparent")
            row.pack(fill="x", anchor="w", pady=1)
            ctk.CTkCheckBox(
                row, text="", variable=check_vars[idx], width=24,
                fg_color=theme.RED, hover_color=theme.RED_HOVER, checkmark_color=theme.WHITE,
            ).pack(side="left")
            text_frame = ctk.CTkFrame(row, fg_color="transparent")
            text_frame.pack(side="left", fill="x", expand=True)
            dur = _format_duration(track.get("duration_ms"))
            subtitle = f"{idx + 1}. {track['artists']}" + (f"  ·  {dur}" if dur else "")
            ctk.CTkLabel(
                text_frame, text=subtitle,
                text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=10),
                anchor="w", justify="left",
            ).pack(anchor="w")
            ctk.CTkLabel(
                text_frame, text=track.get("title") or "(untitled)",
                text_color=theme.TEXT, anchor="w", justify="left", wraplength=480,
            ).pack(anchor="w")

        error_var = tk.StringVar(value="")
        ctk.CTkLabel(dialog, textvariable=error_var, text_color=theme.RED).pack(anchor="w", padx=16)

        def submit():
            selected = [i for i, v in enumerate(check_vars) if v.get()]
            if not selected:
                error_var.set("Select at least one track, or Cancel.")
                return
            result["selection"] = [tracks[i] for i in selected]
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


def confirm_exportify_playlist_choice(root, playlists):
    """Shown when an imported Exportify ZIP ("Export All") contains more
    than one playlist -- lets the user pick exactly one to import right
    now (they can run Import again for another). Must be called from a
    worker thread; blocks until answered. playlists is a list of (title,
    [track dicts]) tuples. Returns the chosen index, or None if cancelled."""
    result = {"index": None}
    event = threading.Event()

    def ask():
        dialog = ctk.CTkToplevel(root)
        dialog.title("Choose a playlist to import")
        dialog.geometry("520x480")
        dialog.configure(fg_color=theme.BG)
        dialog.grab_set()

        ctk.CTkLabel(
            dialog, text=f"This file has {len(playlists)} playlists. Pick one to import now:",
            text_color=theme.TEXT, font=ctk.CTkFont(size=13, weight="bold"),
            wraplength=460, justify="left",
        ).pack(anchor="w", padx=16, pady=(16, 8))

        list_frame = ctk.CTkScrollableFrame(
            dialog, fg_color=theme.SURFACE, scrollbar_button_color=theme.RED,
            scrollbar_button_hover_color=theme.RED_HOVER,
        )
        list_frame.pack(fill="both", expand=True, padx=16, pady=6)

        def choose(idx):
            result["index"] = idx
            event.set()
            dialog.destroy()

        for idx, (title, tracks) in enumerate(playlists):
            ctk.CTkButton(
                list_frame, text=f"{title}  ({len(tracks)} tracks)", anchor="w",
                fg_color=theme.SURFACE, hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT,
                border_width=1, border_color=theme.BORDER, command=lambda i=idx: choose(i),
            ).pack(fill="x", pady=2)

        def cancel():
            result["index"] = None
            event.set()
            dialog.destroy()

        ctk.CTkButton(
            dialog, text="Cancel", command=cancel, fg_color=theme.SURFACE,
            hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT,
            border_width=1, border_color=theme.BORDER,
        ).pack(pady=12)

        dialog.protocol("WM_DELETE_WINDOW", cancel)

    root.after(0, ask)
    event.wait()
    return result["index"]