#!/usr/bin/env bash
# One-click desktop controller starter for Linux / macOS
# Run: ./start_desktop.sh

set -e

cd "$(dirname "$0")"

echo "========================================"
echo "  Remote Control — Desktop Controller"
echo "========================================"
echo ""
echo "[1/1] Starting desktop app (first build may take a minute)…"
echo ""

./gradlew :desktop-controller:run --no-daemon
