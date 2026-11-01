@echo off
REM One-click desktop controller starter for Windows
REM Double-click this file, or run: start_desktop.bat

echo ========================================
echo   Remote Control — Desktop Controller
echo ========================================
echo.

echo [1/2] Building desktop app (first time may take a minute)…
cd /d "%~dp0.."
call gradlew.bat :desktop-controller:run --no-daemon

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Build failed. Make sure Java 17+ is installed.
    pause
    exit /b %ERRORLEVEL%
)
