# Downloads

This directory holds the distributable builds that the landing page (`index.html`) serves:

| File | Purpose |
|------|---------|
| `remote-control-agent-release.apk` | Android Agent APK — install on your phone |
| `remote-control-desktop-windows.zip` | Desktop Controller for Windows |
| `remote-control-desktop-macos.zip` | Desktop Controller for macOS |
| `remote-control-desktop-linux.zip` | Desktop Controller for Linux |

## How files get here

Builds are produced by the GitHub Actions workflow (`.github/workflows/ci-cd.yml`) on every push to `main` and published as a **GitHub Release** at:

https://github.com/lovaerick93-source/test/releases/latest

Because `*.apk` and signing keystores are gitignored (see `.gitignore`), the compiled binaries are **not** committed to this repository. Download them from the release page above, or place them manually in this folder to serve them directly from `http://<your-domain>/static/downloads/`.

## Local testing

To serve the download page locally:

```bash
cd server
pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

Then open `http://localhost:8000/` on your phone or computer.
