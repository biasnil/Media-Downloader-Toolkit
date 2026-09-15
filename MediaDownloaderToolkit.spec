# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller build spec for Media Downloader Toolkit.

Usage:
    pyinstaller MediaDownloaderToolkit.spec

Run this from the project root (the folder containing main.py, Assets/,
Config/, and Script/) -- either directly or via build.bat, which does that
for you. Produces dist/MediaDownloaderToolkit.exe as a single file, with the
Assets folder (app icon) bundled inside it.

To switch from a single exe to a folder build (see the README's
Troubleshooting section on antivirus false positives with --onefile),
change EXE(... exclude_binaries=True) below back to a normal EXE(...) call
and swap the trailing EXE for a COLLECT(...) -- or simplest, just run
`pyinstaller --onedir` from the command line instead of using this spec.
"""

import sys
from PyInstaller.utils.hooks import collect_all

block_cipher = None

# customtkinter and pydub both ship/need things PyInstaller's static
# analysis can miss on its own: customtkinter has its own theme/asset json
# files, and pydub's audioop import (see the "audioop" note below) is
# resolved dynamically in a try/except, which static analysis doesn't
# always follow reliably. collect_all pulls in each package's submodules,
# data files, and binaries so nothing gets silently dropped.
ctk_datas, ctk_binaries, ctk_hiddenimports = collect_all("customtkinter")
pydub_datas, pydub_binaries, pydub_hiddenimports = collect_all("pydub")

a = Analysis(
    ["main.py"],
    pathex=[],
    binaries=ctk_binaries + pydub_binaries,
    datas=[
        ("Assets", "Assets"),
    ] + ctk_datas + pydub_datas,
    hiddenimports=[
        "yt_dlp",
        "pyloudnorm",
        "numpy",
        # pydub imports the stdlib 'audioop' module (falling back to a
        # 'pyaudioop' name in older pydub versions) for raw audio math.
        # Python 3.13 dropped 'audioop' from the stdlib -- the
        # 'audioop-lts' package in requirements.txt restores it under the
        # same 'audioop' module name. Listed explicitly because pydub's
        # try/except import of it can slip past PyInstaller's static
        # analysis, which otherwise silently leaves it out of the build
        # even when it's installed and importable in the build environment.
        "audioop",
        "pyaudioop",
    ] + ctk_hiddenimports + pydub_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="MediaDownloaderToolkit",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,          # windowed app -- no background console window
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon="Assets/icon.icns" if sys.platform == "darwin" else "Assets/icon.ico",
)
