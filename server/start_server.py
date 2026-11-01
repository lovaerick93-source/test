#!/usr/bin/env python3
"""One-click server starter for Remote Control hub.

Runs:
    python start_server.py

It will install Python dependencies (if missing) and start the hub on port 8000.
Supabase is optional — the hub works without it (devices won't persist across restarts).
"""
from __future__ import annotations

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REQ = os.path.join(HERE, "requirements.txt")


def install_deps() -> None:
    print("[1/3] Installing Python dependencies…")
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-r", REQ], check=True)


def start_hub() -> None:
    print("[2/3] Starting hub on http://0.0.0.0:8000")
    print("[3/3] Press Ctrl+C to stop\n")
    os.chdir(HERE)
    subprocess.run([sys.executable, "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"])


def main() -> None:
    try:
        install_deps()
    except Exception as exc:  # noqa: BLE001
        print(f"WARNING: Could not auto-install deps: {exc}")
        print("Try: pip install -r requirements.txt\n")
    start_hub()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nHub stopped.")
