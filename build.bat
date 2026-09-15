@echo off
REM Builds MediaDownloaderToolkit.exe using MediaDownloaderToolkit.spec.
REM Run this from the project root (double-click it, or run it from a
REM command prompt opened in the project folder -- the folder containing
REM main.py, Assets\, Config\, and Script\).

cd /d "%~dp0"

echo Installing/updating PyInstaller and app dependencies...
python -m pip install --upgrade pyinstaller --quiet
if errorlevel 1 (
    echo.
    echo Failed to install PyInstaller. Make sure Python and pip are on PATH.
    pause
    exit /b 1
)
python -m pip install --upgrade -r requirements.txt --quiet
if errorlevel 1 (
    echo.
    echo Failed to install dependencies from requirements.txt.
    pause
    exit /b 1
)

echo.
echo Building MediaDownloaderToolkit.exe ...
python -m PyInstaller MediaDownloaderToolkit.spec --noconfirm
if errorlevel 1 (
    echo.
    echo Build failed -- see the errors above.
    pause
    exit /b 1
)

echo.
echo Done. Find it at: dist\MediaDownloaderToolkit.exe
pause
