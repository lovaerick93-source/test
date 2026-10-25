"""End-to-end check of the hub with two fake clients -- no Android or desktop needed.

Start the hub first, then run::

    python smoke_test.py [ws://127.0.0.1:8000/ws]

It walks the real protocol sequence: handshake, device info, a wrong pairing
code, pairing, phone-side approval, control relay in both directions, screen
frames relayed byte-for-byte, and teardown.  Exits non-zero on the first hard
failure.
"""

from __future__ import annotations

import asyncio
import json
import struct
import sys
import urllib.request

from websockets.asyncio.client import connect

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8000/ws"
HTTP_BASE = URL.replace("ws://", "http://").replace("/ws", "")
DEVICE_ID = "smoke-test-device-001"

passed = 0
failed = 0


def check(label: str, condition: bool, detail: str = "") -> None:
    global passed, failed
    if condition:
        passed += 1
        print(f"  PASS  {label}")
    else:
        failed += 1
        print(f"  FAIL  {label}  {detail}")


async def send(ws, msg_type: str, **payload) -> None:
    await ws.send(json.dumps({"type": msg_type, "payload": payload}))


async def expect(ws, expected_type: str, timeout: float = 5.0) -> dict:
    """Read until `expected_type`; any other type (including broadcasts) is skipped."""
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout)
        if isinstance(raw, (bytes, bytearray)):
            continue  # a screen frame, not an envelope
        frame = json.loads(raw)
        ftype = frame.get("type")
        if ftype == expected_type:
            return frame.get("payload") or {}
        if ftype == "error" and expected_type != "error":
            raise AssertionError(f"hub error while waiting for {expected_type}: {frame.get('payload')}")


async def expect_bytes(ws, timeout: float = 5.0) -> bytes:
    """Read until a binary frame arrives, skipping any JSON envelopes."""
    while True:
        raw = await asyncio.wait_for(ws.recv(), timeout)
        if isinstance(raw, (bytes, bytearray)):
            return bytes(raw)


# Mirrors com.remotectl.protocol.VideoFrame: kind, flags, codec, reserved,
# ptsMs, width, height, all little-endian.
FRAME_HEADER = struct.Struct("<BBBBIHH")


def http_devices() -> list:
    with urllib.request.urlopen(f"{HTTP_BASE}/devices", timeout=5) as response:
        return json.loads(response.read().decode())["devices"]


async def main() -> int:
    async with connect(URL) as agent, connect(URL) as controller:
        # ---------------------------------------------------------- handshake
        print("\n[1] agent handshake")
        await send(agent, "hello", role="agent", device_id=DEVICE_ID, protocol_version=1)
        welcome = await expect(agent, "welcome")
        code = welcome.get("pairing_code")
        check(
            "phone got a 6-digit pairing code",
            isinstance(code, str) and len(code) == 6 and code.isdigit(),
            f"got {code!r}",
        )
        check("welcome echoes device id", welcome.get("device_id") == DEVICE_ID)

        await send(
            agent,
            "device.info",
            device_id=DEVICE_ID,
            name="Smoke Phone",
            model="Pixel-Test",
            manufacturer="TestCo",
            android_version="15",
            sdk_int=35,
            screen_width=1080,
            screen_height=2400,
            battery_percent=87,
            can_capture=False,
            can_inject_input=False,
            installed_app_count=42,
        )
        # A ping round trip proves device.info was processed before we go on,
        # because the hub handles one socket's frames strictly in order.
        await send(agent, "ping", nonce="info-sync")
        pong = await expect(agent, "pong")
        check("hub responds to ping", pong.get("echo") == "info-sync", f"got {pong}")

        print("\n[2] controller handshake")
        await send(controller, "hello", role="controller", protocol_version=1)
        await expect(controller, "welcome")
        listing = await expect(controller, "device.list")
        ids = [d["device_id"] for d in listing.get("devices", [])]
        check("phone shows up in the desktop's device list", DEVICE_ID in ids, f"got {ids}")
        entry = next((d for d in listing["devices"] if d["device_id"] == DEVICE_ID), {})
        check("desktop sees the phone's model", entry.get("info", {}).get("model") == "Pixel-Test", f"got {entry.get('info')}")
        check("device starts unpaired", entry.get("paired") is False)

        print("\n[3] pairing code is enforced")
        wrong = "111111" if code != "111111" else "222222"
        await send(controller, "connect.request", device_id=DEVICE_ID, pairing_code=wrong)
        error = await expect(controller, "error")
        check("wrong pairing code is rejected", error.get("reason") == "bad_pairing_code", f"got {error}")

        print("\n[4] pairing with phone-side approval")
        await send(controller, "connect.request", device_id=DEVICE_ID, pairing_code=code)
        pending = await expect(controller, "connect.pending")
        check("desktop is told to wait for the phone", pending.get("device_id") == DEVICE_ID)

        incoming = await expect(agent, "connect.incoming")
        check("phone received the consent prompt", bool(incoming.get("controller")), f"got {incoming}")

        await send(agent, "connect.response", accepted=True)
        paired_agent = await expect(agent, "peer.connected")
        paired_controller = await expect(controller, "peer.connected")
        check("phone sees the session", paired_agent.get("device_id") == DEVICE_ID)
        check(
            "desktop sees the session and got device info",
            paired_controller.get("info", {}).get("model") == "Pixel-Test",
            f"got {paired_controller.get('info')}",
        )

        print("\n[5] control relay, both directions")
        await send(controller, "ctl.tap", x=540, y=1200)
        tap = await expect(agent, "ctl.tap")
        check("desktop -> phone command relayed intact", tap == {"x": 540, "y": 1200}, f"got {tap}")

        await send(agent, "evt.session", mirroring=True, fps=60)
        event = await expect(controller, "evt.session")
        check("phone -> desktop event relayed intact", event.get("fps") == 60, f"got {event}")

        print("\n[6] screen frames relay byte-for-byte")
        header = FRAME_HEADER.pack(1, 1, 0, 0, 1234, 720, 1280)
        picture = b"\xff\xd8" + b"pretend-this-is-a-jpeg" + b"\xff\xd9"
        sent_frame = header + picture

        await agent.send(sent_frame)
        relayed = await expect_bytes(controller)
        check("frame reaches the desktop unchanged", relayed == sent_frame, f"got {len(relayed)} bytes")
        kind, flags, codec, _reserved, pts, width, height = FRAME_HEADER.unpack(relayed[:12])
        check(
            "frame header survives the hub",
            (kind, flags, codec, pts, width, height) == (1, 1, 0, 1234, 720, 1280),
            f"got {(kind, flags, codec, pts, width, height)}",
        )
        check("picture bytes are untouched", relayed[12:] == picture)

        print("\n[7] teardown")
        await send(controller, "disconnect")
        gone = await expect(agent, "peer.disconnected")
        check("phone is told when the desktop hangs up", gone.get("reason") == "session_closed_by_peer", f"got {gone}")

        await send(controller, "ctl.tap", x=1, y=1)
        refused = await expect(controller, "error")
        check("commands are refused once unpaired", refused.get("reason") == "not_paired", f"got {refused}")

        print("\n[8] HTTP surface")
        devices = http_devices()
        check("GET /devices lists the phone", any(d["device_id"] == DEVICE_ID for d in devices), f"got {devices}")

    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
