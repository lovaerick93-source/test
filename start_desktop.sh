#!/usr/bin/env bash
# One-click desktop controller launcher for Linux / macOS
# Double-click this file — no terminal, no Gradle, no setup.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/remote-control-desktop.jar"

echo "========================================"
echo "  Remote Control — Desktop Controller"
echo "========================================"
echo ""

if ! command -v java &>/dev/null; then
    echo "Java not found. Please install Java 17+."
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "Desktop app not found at $JAR_PATH"
    echo "Please ensure remote-control-desktop.jar is next to this file."
    exit 1
fi

echo "Starting Remote Control Desktop…"
java -jar "$JAR_PATH"
