@echo off
REM One-click desktop controller launcher for Windows
REM Double-click this file — no terminal, no Gradle, no setup.

echo ========================================
echo   Remote Control — Desktop Controller
echo ========================================
echo.

REM Locate Java
set "JAVA_EXE=java.exe"
where java.exe >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Java not found in PATH. Please install Java 17+.
    pause
    exit /b 1
)

REM Locate the bundled JAR next to this script
set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%remote-control-desktop.jar"

if not exist "%JAR_PATH%" (
    echo Desktop app not found at "%JAR_PATH%"
    echo Please ensure remote-control-desktop.jar is next to this file.
    pause
    exit /b 1
)

echo Starting Remote Control Desktop…
"%JAVA_EXE%" -jar "%JAR_PATH%"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo The app exited with an error.
    pause
    exit /b %ERRORLEVEL%
)
