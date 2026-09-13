"""The 'Download History' viewer window."""

import customtkinter as ctk

import Script.theme as theme
from Script.context_menu import add_context_menu
from Script.history import HISTORY_PATH, load_history


def open_history_window(root):
    win = ctk.CTkToplevel(root)
    win.title("Download History")
    win.geometry("660x440")
    win.configure(fg_color=theme.BG)

    top = ctk.CTkFrame(win, fg_color="transparent")
    top.pack(fill="x", padx=16, pady=(12, 6))

    box = ctk.CTkTextbox(
        win, fg_color=theme.SURFACE, text_color=theme.TEXT, border_width=1,
        border_color=theme.BORDER, scrollbar_button_color=theme.RED,
        scrollbar_button_hover_color=theme.RED_HOVER,
    )

    def populate():
        entries = load_history()
        box.configure(state="normal")
        box.delete("1.0", "end")
        if not entries:
            box.insert("end", "No downloads recorded yet.\n")
        else:
            for e in reversed(entries):
                source = e.get("source")
                tag = f"[{source}] " if source else ""
                line = (
                    f"[{e.get('timestamp', '?')}] {e.get('status', '?').upper():9s} "
                    f"{tag}{e.get('title') or e.get('url', '')}"
                )
                if e.get("detail"):
                    line += f"  —  {e['detail']}"
                box.insert("end", line + "\n")
        box.configure(state="disabled")

    ctk.CTkButton(
        top, text="Refresh", command=populate, fg_color=theme.RED,
        hover_color=theme.RED_HOVER, text_color=theme.WHITE, width=90,
    ).pack(side="left")
    ctk.CTkLabel(
        top, text=f"Log file: {HISTORY_PATH}", text_color=theme.TEXT_MUTED,
        font=ctk.CTkFont(size=11),
    ).pack(side="left", padx=10)

    box.pack(fill="both", expand=True, padx=16, pady=(0, 16))
    add_context_menu(box)

    populate()