"""Entry point. Run this file to launch the Media Downloader Toolkit —
YouTube, Other Sites (Instagram/X/Reddit/TikTok/etc.), the Loudness
Normalizer, and the Format Converter, all in one window."""

import customtkinter as ctk

import Script.theme as theme
from Config.app_settings import AppSettings
from Script.youtube_tab import YouTubeTab
from Script.other_sites_tab import OtherSitesTab
from Script.normalizer_tab import NormalizerTab
from Script.converter_tab import ConverterTab
from Script.settings_dialog import open_settings_window


def main():
    ctk.set_default_color_theme("blue")  # base widget geometry; colors are overridden per-widget

    root = ctk.CTk()
    root.title("Media Downloader Toolkit")
    root.geometry("760x720")
    root.configure(fg_color=theme.BG)

    settings = AppSettings(root)
    ctk.set_appearance_mode("Dark" if settings.dark_mode.get() else "Light")
    theme.apply_ui_scale(settings.ui_scale.get())

    # --- Shared header: title + gear icon + appearance toggle -------------
    header = ctk.CTkFrame(root, fg_color=theme.RED, corner_radius=0, height=52)
    header.pack(fill="x", side="top")
    header.pack_propagate(False)
    ctk.CTkLabel(
        header, text="▶  Media Downloader Toolkit",
        text_color=theme.WHITE, font=ctk.CTkFont(size=16, weight="bold"),
    ).pack(side="left", padx=16)

    def toggle_appearance():
        ctk.set_appearance_mode("Dark" if settings.dark_mode.get() else "Light")
        settings.save()

    ctk.CTkSwitch(
        header, text="Dark Mode", variable=settings.dark_mode,
        onvalue=True, offvalue=False, command=toggle_appearance,
        progress_color=theme.RED_PRESSED, button_color=theme.WHITE,
        button_hover_color=theme.SURFACE_HOVER, fg_color=theme.RED_HOVER,
        text_color=theme.WHITE,
    ).pack(side="right", padx=16)

    ctk.CTkButton(
        header, text="⚙", width=36, command=lambda: open_settings_window(root, settings),
        fg_color=theme.RED_HOVER, hover_color=theme.RED_PRESSED, text_color=theme.WHITE,
        font=ctk.CTkFont(size=16),
    ).pack(side="right", padx=(0, 4))

    # --- Tabs: one per tool -------------------------------------------------
    tabview = ctk.CTkTabview(
        root,
        fg_color=theme.BG,
        segmented_button_fg_color=theme.SURFACE,
        segmented_button_selected_color=theme.RED,
        segmented_button_selected_hover_color=theme.RED_HOVER,
        segmented_button_unselected_color=theme.SURFACE,
        segmented_button_unselected_hover_color=theme.SURFACE_HOVER,
        text_color=theme.TEXT,
    )
    tabview.pack(fill="both", expand=True)

    youtube_frame = tabview.add("YouTube")
    other_sites_frame = tabview.add("Other Sites")
    normalizer_frame = tabview.add("Normalizer")
    converter_frame = tabview.add("Converter")

    YouTubeTab(youtube_frame, root, settings)
    OtherSitesTab(other_sites_frame, root, settings)
    NormalizerTab(normalizer_frame, root)
    ConverterTab(converter_frame, root)

    root.mainloop()


if __name__ == "__main__":
    main()