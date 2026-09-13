"""The gear-icon settings dialog: thumbnail/cover-art embedding, subtitle
downloads, and a download speed limit. These are shared across the YouTube
and Other Sites tabs since both go through yt-dlp."""

import customtkinter as ctk

import Script.theme as theme
from Script.context_menu import add_context_menu


def open_settings_window(root, settings):
    win = ctk.CTkToplevel(root)
    win.title("Settings")
    win.geometry("460x520")
    win.minsize(420, 320)
    win.resizable(True, True)
    win.configure(fg_color=theme.BG)
    win.grab_set()

    pad = {"padx": 16, "pady": 8}

    # Save/Close are packed first, with side="bottom", so they always keep
    # their reserved space at the bottom of the window no matter how tall
    # the settings content above ends up being (font/DPI rendering can vary
    # enough between platforms that a fixed window height alone isn't
    # reliable -- this way the buttons can never get pushed off-screen).
    def save_and_close():
        settings.save()
        win.destroy()

    btn_frame = ctk.CTkFrame(win, fg_color="transparent")
    btn_frame.pack(side="bottom", pady=18)
    ctk.CTkButton(
        btn_frame, text="Save", command=save_and_close, fg_color=theme.RED,
        hover_color=theme.RED_HOVER, text_color=theme.WHITE, font=ctk.CTkFont(weight="bold"),
    ).pack(side="left", padx=6)
    ctk.CTkButton(
        btn_frame, text="Close", command=win.destroy, fg_color=theme.SURFACE,
        hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT, border_width=1, border_color=theme.BORDER,
    ).pack(side="left", padx=6)

    # Everything else lives in a scrollable area above that footer, so if
    # the content is ever taller than the window (more settings added later,
    # a different platform's font rendering, a small window after manual
    # resize), it scrolls instead of getting clipped.
    scroll = ctk.CTkScrollableFrame(
        win, fg_color=theme.BG, scrollbar_button_color=theme.RED,
        scrollbar_button_hover_color=theme.RED_HOVER,
    )
    scroll.pack(side="top", fill="both", expand=True)

    def section_label(text):
        ctk.CTkLabel(
            scroll, text=text, text_color=theme.TEXT, font=ctk.CTkFont(size=13, weight="bold"),
            anchor="w",
        ).pack(anchor="w", padx=16, pady=(14, 2))

    ctk.CTkLabel(
        scroll, text="Applies to the YouTube and Other Sites tabs.",
        text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), anchor="w",
    ).pack(anchor="w", padx=16, pady=(12, 0))

    section_label("Thumbnail")
    ctk.CTkCheckBox(
        scroll, text="Embed thumbnail as cover art", variable=settings.embed_thumbnail,
        fg_color=theme.RED, hover_color=theme.RED_HOVER, checkmark_color=theme.WHITE,
        text_color=theme.TEXT,
    ).pack(anchor="w", padx=16)

    section_label("Subtitles")
    ctk.CTkCheckBox(
        scroll, text="Download subtitles (video downloads only)", variable=settings.download_subtitles,
        fg_color=theme.RED, hover_color=theme.RED_HOVER, checkmark_color=theme.WHITE,
        text_color=theme.TEXT,
    ).pack(anchor="w", padx=16)
    lang_row = ctk.CTkFrame(scroll, fg_color="transparent")
    lang_row.pack(fill="x", padx=16, pady=(6, 0))
    ctk.CTkLabel(lang_row, text="Languages:", text_color=theme.TEXT, width=90, anchor="w").pack(side="left")
    lang_entry = ctk.CTkEntry(
        lang_row, textvariable=settings.subtitle_langs, fg_color=theme.SURFACE,
        text_color=theme.TEXT, border_color=theme.BORDER,
    )
    lang_entry.pack(side="left", fill="x", expand=True)
    add_context_menu(lang_entry)
    ctk.CTkLabel(
        scroll, text="Comma-separated language codes, e.g. en,es,ja",
        text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), anchor="w",
    ).pack(anchor="w", padx=16)

    section_label("Speed limit")
    limit_row = ctk.CTkFrame(scroll, fg_color="transparent")
    limit_row.pack(fill="x", padx=16)
    ctk.CTkLabel(limit_row, text="Max speed:", text_color=theme.TEXT, width=90, anchor="w").pack(side="left")
    limit_entry = ctk.CTkEntry(
        limit_row, textvariable=settings.speed_limit, fg_color=theme.SURFACE,
        text_color=theme.TEXT, border_color=theme.BORDER,
    )
    limit_entry.pack(side="left", fill="x", expand=True)
    add_context_menu(limit_entry)
    ctk.CTkLabel(
        scroll, text="e.g. 500K or 2M — leave blank for unlimited",
        text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), anchor="w",
    ).pack(anchor="w", padx=16)

    section_label("Downloads")
    concurrent_row = ctk.CTkFrame(scroll, fg_color="transparent")
    concurrent_row.pack(fill="x", padx=16)
    ctk.CTkLabel(concurrent_row, text="At once:", text_color=theme.TEXT, width=90, anchor="w").pack(side="left")
    ctk.CTkOptionMenu(
        concurrent_row, variable=settings.max_concurrent_downloads,
        values=["1", "2", "3", "4", "5"],
        fg_color=theme.SURFACE, button_color=theme.RED, button_hover_color=theme.RED_HOVER,
        text_color=theme.TEXT, dropdown_fg_color=theme.SURFACE, dropdown_text_color=theme.TEXT,
        dropdown_hover_color=theme.SURFACE_HOVER, width=80,
    ).pack(side="left")
    ctk.CTkLabel(
        scroll,
        text="How many downloads run at the same time -- both separate pasted links and "
             "videos within a playlist. Higher numbers are faster but more likely to get "
             "rate-limited. Note this isn't a strict global cap: if you have multiple "
             "playlists going at once, each one respects this number independently.",
        text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), anchor="w",
        wraplength=400, justify="left",
    ).pack(anchor="w", padx=16, pady=(2, 0))

    section_label("Display")
    scale_row = ctk.CTkFrame(scroll, fg_color="transparent")
    scale_row.pack(fill="x", padx=16)
    ctk.CTkLabel(scale_row, text="UI scale:", text_color=theme.TEXT, width=90, anchor="w").pack(side="left")
    ctk.CTkOptionMenu(
        scale_row, variable=settings.ui_scale, values=theme.UI_SCALE_OPTIONS,
        command=theme.apply_ui_scale, fg_color=theme.SURFACE, button_color=theme.RED,
        button_hover_color=theme.RED_HOVER, text_color=theme.TEXT,
        dropdown_fg_color=theme.SURFACE, dropdown_text_color=theme.TEXT,
        dropdown_hover_color=theme.SURFACE_HOVER,
    ).pack(side="left")
    ctk.CTkLabel(
        scroll,
        text="If the app looks too small on Linux (common on some desktops/Wayland "
             "setups where auto-detection doesn't pick up screen scaling), set this "
             "manually. Takes effect immediately.",
        text_color=theme.TEXT_MUTED, font=ctk.CTkFont(size=11), anchor="w",
        wraplength=400, justify="left",
    ).pack(anchor="w", padx=16, pady=(2, 0))

    win.protocol("WM_DELETE_WINDOW", win.destroy)