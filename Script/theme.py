"""YouTube-inspired color palette.

Every value below (except RED itself) is a (light_mode, dark_mode) tuple.
CustomTkinter widgets accept these tuples directly and swap automatically
whenever ctk.set_appearance_mode() switches between "Light" and "Dark" —
no manual re-styling of individual widgets is needed.
"""

import customtkinter as ctk

RED = "#FF0000"          # YouTube brand red — used the same in both modes
RED_HOVER = "#CC0000"
RED_PRESSED = "#990000"

BG = ("#FFFFFF", "#0F0F0F")             # window background
SURFACE = ("#F2F2F2", "#272727")        # input fields, log/URL boxes, secondary panels
SURFACE_HOVER = ("#E5E5E5", "#3F3F3F")  # hover state for secondary (non-red) buttons
BORDER = ("#E0E0E0", "#3F3F3F")
TEXT = ("#0F0F0F", "#FFFFFF")
TEXT_MUTED = ("#606060", "#AAAAAA")
WHITE = "#FFFFFF"

FONT_FAMILY = "Roboto"  # falls back to a system default automatically if unavailable

UI_SCALE_OPTIONS = ["Auto", "100%", "125%", "150%", "175%", "200%"]


def apply_ui_scale(scale_text):
    """Applies a manual widget/window scale override, or leaves CustomTkinter's
    own automatic DPI detection in place when scale_text is 'Auto' (the
    default). A manual override exists because that auto-detection is
    unreliable on some Linux desktops and Wayland setups in particular --
    the UI can end up rendering far too small there even at full screen,
    since CustomTkinter never detects that the display needed scaling in
    the first place."""
    if not scale_text or scale_text == "Auto":
        return
    try:
        factor = float(scale_text.rstrip("%")) / 100
    except ValueError:
        return
    ctk.set_widget_scaling(factor)
    ctk.set_window_scaling(factor)