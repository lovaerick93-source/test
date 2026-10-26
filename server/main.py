"""HTTP + WebSocket entrypoint for the remote-control hub.

Run it with::

    python -m uvicorn main:app --host 0.0.0.0 --port 8000

An agent (phone) and a controller (desktop) each open ``/ws`` and begin with a
``hello`` frame.  After the handshake a controller can list devices, pair using
the code the phone displays, and then exchange control frames that the hub
relays without inspecting them.

Two kinds of traffic share the socket:

* **Text** frames are JSON envelopes, ``{"type": ..., "payload": {...}}``, used
  for control and events.
* **Binary** frames are screen images: a 12-byte header then encoded picture
  data.  The hub forwards them verbatim to the paired peer and never looks
  inside, which keeps per-frame work out of Python.  See ``protocol`` module
  ``VideoFrame`` for the exact layout.
"""

from __future__ import annotations

import json
import logging
import time
import os
from contextlib import asynccontextmanager
from typing import Any, Dict

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

from hub import Connection, Hub, new_id
from models import PROTOCOL_VERSION, DeviceInfo, Hello, Msg

log = logging.getLogger("hub")
hub = Hub()


@asynccontextmanager
async def lifespan(_: FastAPI):
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    log.info("remote-control hub starting (protocol v%d)", PROTOCOL_VERSION)
    yield
    log.info("remote-control hub stopped")


app = FastAPI(title="Remote Control Hub", version="0.1.0", lifespan=lifespan)

# Mount the downloads folder so users can download the APK and Desktop app
# Files should be placed in server/static/downloads/
if not os.path.exists("static/downloads"):
    os.makedirs("static/downloads", exist_ok=True)

app.mount("/static", StaticFiles(directory="static"), name="static")

@app.get("/")
async def root():
    # Serve the download page by default
    index_path = os.path.join("static", "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    
    return {
        "service": "remote-control-hub",
        "version": "0.1.0",
        "protocol_version": PROTOCOL_VERSION,
        "agents_online": hub.agent_count(),
        "controllers_online": hub.controller_count(),
        "downloads": "/static/index.html"
    }


@app.get("/devices")
async def devices() -> Dict[str, Any]:
    return {"devices": hub.device_views()}


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket) -> None:
    await ws.accept()
    conn = Connection(conn_id=new_id("c"), ws=ws)
    handshaken = False

    try:
        first = await _receive_json(ws)
        handshaken = await _handshake(conn, first)
        if not handshaken:
            return

        while True:
            message = await ws.receive()
            if message.get("type") == "websocket.disconnect":
                break

            # Screen frames bypass JSON entirely: straight through to the peer.
            frame = message.get("bytes")
            if frame is not None:
                await hub.relay_binary(conn, frame)
                continue

            text = message.get("text")
            if text is None:
                continue
            await _dispatch(conn, json.loads(text))

    except WebSocketDisconnect:
        log.info("client gone: %s (%s)", conn.conn_id, conn.role)
    except (json.JSONDecodeError, ValueError):
        await conn.send(Msg.ERROR, reason="malformed_frame")
    except Exception:
        log.exception("unhandled error in session %s", conn.conn_id)
        await conn.send(Msg.ERROR, reason="internal_error")
    finally:
        if handshaken:
            await hub.remove(conn)
        try:
            await ws.close()
        except Exception:
            pass


async def _receive_json(ws: WebSocket) -> Any:
    """Wait for the opening JSON frame, skipping anything that cannot be it."""
    while True:
        message = await ws.receive()
        if message.get("type") == "websocket.disconnect":
            raise WebSocketDisconnect(message.get("code") or 1000)
        text = message.get("text")
        if text is not None:
            return json.loads(text)
        # A binary frame before the handshake has no peer to go to; drop it.


async def _handshake(conn: Connection, first: Dict[str, Any]) -> bool:
    """Validate the opening frame and register the client. False means goodbye."""
    if not isinstance(first, dict) or first.get("type") != Msg.HELLO:
        await conn.send(Msg.ERROR, reason="expected_hello", got=first.get("type") if isinstance(first, dict) else None)
        return False

    try:
        hello = Hello(**(first.get("payload") or {}))
    except Exception:
        await conn.send(Msg.ERROR, reason="malformed_hello")
        return False

    if hello.protocol_version != PROTOCOL_VERSION:
        await conn.send(
            Msg.ERROR,
            reason="protocol_version_mismatch",
            expected=PROTOCOL_VERSION,
            got=hello.protocol_version,
        )
        return False

    if hello.role == "agent":
        if not hello.device_id:
            await conn.send(Msg.ERROR, reason="agent_must_send_device_id")
            return False
        code = await hub.add_agent(
            conn,
            DeviceInfo(device_id=hello.device_id),
            hello.pairing_code,
            hello.auto_approve,
        )
        await conn.send(
            Msg.WELCOME,
            conn_id=conn.conn_id,
            role="agent",
            device_id=hello.device_id,
            pairing_code=code,
        )
        return True

    if hello.role == "controller":
        await hub.add_controller(conn)
        await conn.send(Msg.WELCOME, conn_id=conn.conn_id, role="controller")
        # Sent after the welcome so the client sees a fixed, predictable order.
        await hub.send_device_list(conn)
        return True

    await conn.send(Msg.ERROR, reason="unknown_role", role=hello.role)
    return False


async def _dispatch(conn: Connection, frame: Dict[str, Any]) -> None:
    """Handle hub-local types; relay everything else to the paired peer."""
    if not isinstance(frame, dict):
        await conn.send(Msg.ERROR, reason="malformed_frame")
        return

    msg_type = frame.get("type")
    payload = frame.get("payload") or {}
    if not isinstance(msg_type, str) or not isinstance(payload, dict):
        await conn.send(Msg.ERROR, reason="malformed_frame")
        return

    if msg_type == Msg.PING:
        await conn.send(Msg.PONG, echo=payload.get("nonce"), server_time=time.time())
        return

    if msg_type == Msg.DEVICE_INFO:
        if conn.role != "agent":
            await conn.send(Msg.ERROR, reason="agents_only")
            return
        try:
            info = DeviceInfo(**payload)
        except Exception:
            await conn.send(Msg.ERROR, reason="malformed_device_info")
            return
        conn.device_id = info.device_id
        await hub.update_device_info(conn, info)
        return

    if msg_type == Msg.CONNECT_REQUEST:
        if conn.role != "controller":
            await conn.send(Msg.ERROR, reason="controllers_only")
            return
        await hub.request_connection(
            conn,
            str(payload.get("device_id", "")),
            str(payload.get("pairing_code", "")),
        )
        return

    if msg_type == Msg.CONNECT_RESPONSE:
        if conn.role != "agent":
            await conn.send(Msg.ERROR, reason="agents_only")
            return
        await hub.respond_connection(conn, bool(payload.get("accepted", False)))
        return

    if msg_type == Msg.DISCONNECT:
        await hub.unpair(conn)
        return

    await hub.relay(conn, msg_type, payload)
