# Remote Control — Quick Start

Three steps to control your Android phone from your desktop.

---

## Step 1: Start the hub (computer)

Open a terminal in the project folder and run:

```bash
cd server
python start_server.py
```

This installs Python dependencies and starts the hub on `http://0.0.0.0:8000`.

Leave this running.

---

## Step 2: Install the agent (phone)

1. Open `http://<your-computer-ip>:8000/` in your phone's browser
   - Example: `http://192.168.1.5:8000/`
2. Download the **Android Agent APK** and install it
3. Open the app on your phone
4. Enter your computer's address (e.g. `192.168.1.5:8000`)
5. Tap **START AGENT**
6. A 6-digit pairing code appears — write it down

---

## Step 3: Connect (desktop)

1. Run the desktop controller:
   - **Windows:** double-click `start_desktop.bat`
   - **Mac/Linux:** run `./start_desktop.sh`
2. Click **Connect** (hub address is pre-filled)
3. Pick your phone from the list
4. Enter the 6-digit code from your phone
5. Click **Pair** — then tap **Accept** on your phone when it asks
6. Click your phone's screen to tap/drag — you're in control

---

## What works

| Feature | How |
|---------|-----|
| See phone screen | Click **Start mirroring** in the desktop app |
| Tap / swipe | Click or drag on the phone's screen image |
| Type text | Click a text field on the phone, type from desktop |
| Launch apps | Double-click any app in the list |
| Stop control | Tap **Stop agent** on the phone, or **Disconnect** on desktop |

---

## Files you'll touch

| File | Purpose |
|------|---------|
| `server/start_server.py` | One-command hub starter |
| `start_desktop.bat` / `start_desktop.sh` | One-command desktop starter |
| `server/static/index.html` | Download page (phone & desktop) |
| `android-agent/` | Phone app (build with Android Studio or `./gradlew`) |
| `desktop-controller/` | Desktop app (runs via Gradle) |

That's it. No Cloudflare, no Supabase, no complex setup required for local use.
