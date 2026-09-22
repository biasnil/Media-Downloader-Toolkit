"""Blocking prompt shown when the best YouTube match for a track (or a
Spotify playlist title / raw fallback text standing in for one) doesn't
clear the confidence threshold (Settings > Spotify, default 80%), or
nothing came back at all. Modeled on the retry/skip/stop pattern in
Script/playlist_dialog.py, but with a "download anyway" option instead of
"stop the whole batch", since here each item is judged independently
rather than as part of one all-or-nothing playlist run -- and, like that
dialog, a "remember" checkbox so one decision can cover the rest of the
batch instead of asking per track."""

import threading
import tkinter as tk

import customtkinter as ctk

import Script.theme as theme


def confirm_low_confidence_match(root, item_label, best_candidate, best_score):
    """Must be called from a worker thread; blocks that thread until
    answered. Builds the dialog on the Tk main thread via root.after().
    Returns (action, remember): action is 'continue', 'skip', or 'retry';
    remember is True if the user wants that same action applied
    automatically to the rest of this batch without asking again (only
    meaningful for 'skip'/'continue' -- the caller should not persist
    'retry' the same way, since auto-retrying every low-confidence track
    forever would just hang; 'continue' isn't offered at all when
    best_candidate is None, since there's nothing to continue with)."""
    result = {"action": "skip", "remember": False}
    event = threading.Event()

    def ask():
        dialog = ctk.CTkToplevel(root)
        dialog.title("No confident match")
        dialog.geometry("480x320")
        dialog.configure(fg_color=theme.BG)
        dialog.grab_set()

        ctk.CTkLabel(
            dialog, text=item_label, text_color=theme.TEXT,
            font=ctk.CTkFont(size=13, weight="bold"), wraplength=440, justify="left",
        ).pack(anchor="w", padx=16, pady=(16, 4))

        if best_candidate is not None:
            detail = (
                f"Best YouTube match: \"{best_candidate.get('title', '?')}\" "
                f"— {best_score}% confidence (below the cutoff)"
            )
            body_text = (
                "Nothing found on Spotify + YouTube that's confident enough. "
                "Download the closest match anyway, skip this one, or search again?"
            )
        else:
            detail = "No results found on YouTube for this at all."
            body_text = "Nothing found on Spotify + YouTube. Skip this one, or search again?"

        ctk.CTkLabel(
            dialog, text=detail, text_color=theme.TEXT_MUTED,
            font=ctk.CTkFont(size=11), wraplength=440, justify="left",
        ).pack(anchor="w", padx=16, pady=(0, 10))

        ctk.CTkLabel(
            dialog, text=body_text, text_color=theme.TEXT, wraplength=440, justify="left",
        ).pack(anchor="w", padx=16)

        remember_var = tk.BooleanVar(value=False)
        ctk.CTkCheckBox(
            dialog, text="Do this for the rest of this batch without asking again",
            variable=remember_var, fg_color=theme.RED, hover_color=theme.RED_HOVER,
            checkmark_color=theme.WHITE, text_color=theme.TEXT,
        ).pack(anchor="w", padx=16, pady=(12, 0))

        def choose(action):
            result["action"] = action
            result["remember"] = remember_var.get()
            event.set()
            dialog.destroy()

        btn_frame = ctk.CTkFrame(dialog, fg_color="transparent")
        btn_frame.pack(pady=18)
        if best_candidate is not None:
            ctk.CTkButton(
                btn_frame, text="Continue anyway", command=lambda: choose("continue"),
                fg_color=theme.RED, hover_color=theme.RED_HOVER, text_color=theme.WHITE,
                font=ctk.CTkFont(weight="bold"),
            ).pack(side="left", padx=6)
        ctk.CTkButton(
            btn_frame, text="Skip", command=lambda: choose("skip"),
            fg_color=theme.SURFACE, hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT,
            border_width=1, border_color=theme.BORDER,
        ).pack(side="left", padx=6)
        ctk.CTkButton(
            btn_frame, text="Retry search", command=lambda: choose("retry"),
            fg_color=theme.SURFACE, hover_color=theme.SURFACE_HOVER, text_color=theme.TEXT,
            border_width=1, border_color=theme.BORDER,
        ).pack(side="left", padx=6)

        dialog.protocol("WM_DELETE_WINDOW", lambda: choose("skip"))

    root.after(0, ask)
    event.wait()
    return result["action"], result["remember"]