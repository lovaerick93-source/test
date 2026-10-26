
# Remote Control

A cross-device remote control system that lets you control an Android phone from a desktop computer.

```
┌──────────────────┐        WS        ┌──────────────────┐
│  android-agent   │◄──────────────►  │   server (hub)   │
│    (Phone)       │   JSON + binary  │   (Python/FastAPI)│
└──────────────────┘                  └────────┬─────────┘
                                                 │
                                      ┌──────────▼──────────┐
                                      │ desktop-controller  │
                                      │   (Java/Swing app)  │
                                      └─────────────────────┘
```

## Architecture

| Module | Language | Purpose |
|--------|----------|---------|
| `protocol/` | Kotlin | Shared message definitions + wire protocol |
| `android-agent/` | Kotlin (Android) | Phone app: screen mirroring + remote input |
| `desktop-controller/` | Kotlin/Java (Swing) | Desktop app: app drawer, screen view, control |
| `server/` | Python (FastAPI) | Hub: WebSocket relay, pairing, device registry |
| `supabase/` | SQL | Database migrations for device persistence |

## Features

- **Screen mirroring** — See your phone screen on your desktop in real time
- **Remote input** — Tap, swipe, type text, press keys
- **App launcher** — View installed apps and launch them remotely
- **Pairing** — 6-digit pairing codes with phone-side consent
- **File transfer** — Push/pull files between desktop and phone
- **Clipboard sync** — Copy/paste between devices
- **Offline support** — Devices are registered in Supabase, so they appear when you reconnect

## Getting Started

### Prerequisites

- **Java 17+** (for the desktop controller)
- **Android Studio / SDK** (for the Android agent)
- **Python 3.10+** (for the server)
- **Supabase project** (for device persistence)

### Running the Server

```bash
cd server
cp .env.example .env   # already configured with dev values
pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

The hub will be available at `http://localhost:8000`.

### Building the Android Agent

1. Open `android-agent/` in Android Studio
2. Sync Gradle
3. Create `android-agent/keystore.properties` with your signing key (required for release builds)
4. Build the debug APK (`Build → Build Bundle(s) / APK(s) → Build APK(s)`)
5. Install on your phone

### Building the Desktop Controller

```bash
cd desktop-controller
# Ensure Java 17+ is installed
gradle run
```

## Project Structure

```
.
├── android-agent/          # Phone app (Kotlin, Android)
│   └── src/main/kotlin/com/remotectl/agent/
├── desktop-controller/     # Desktop app (Kotlin + Swing)
│   └── src/main/kotlin/com/remotectl/desktop/
├── protocol/               # Shared message definitions (Kotlin)
├── server/                 # Hub server (Python/FastAPI)
│   ├── main.py             # FastAPI entrypoint
│   ├── hub.py              # Connection registry + pairing state machine
│   ├── models.py           # Pydantic models
│   └── static/             # Static downloads + index.html
├── supabase/               # Database migrations
└── gradle/                 # Gradle wrapper
```

## Testing

Run the end-to-end smoke test on the server:

```bash
cd server
python smoke_test.py
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Open a pull request

## License

MIT — see [LICENSE](LICENSE) for details.

## Links

- **GitHub**: https://github.com/lovaerick93-source/test
- **Protocol**: `protocol/src/main/kotlin/com/remotectl/protocol/Messages.kt`
- **Server docs**: `server/main.py` (top of file)

﻿# test
