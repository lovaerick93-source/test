"""Connection registry, pairing state machine and message routing.

The hub is deliberately ignorant of control traffic: once an agent and a
controller are paired it forwards their ``ctl.*`` / ``evt.*`` frames verbatim.
That keeps input latency out of Python and lets both Kotlin ends evolve their
own command set without a server change.
"""

from __future__ import annotations

import asyncio
import logging
import secrets
import time
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from dotenv import load_dotenv
from supabase import create_client, Client

from fastapi import WebSocket

from models import DeviceInfo, Msg

# Load environment variables from .env
load_dotenv()

log = logging.getLogger("hub")


def new_id(prefix: str) -> str:
    return f"{prefix}_{secrets.token_hex(6)}"


@dataclass
class Connection:
    """One live WebSocket plus everything the hub knows about it."""

    conn_id: str
    ws: WebSocket
    role: str = "unknown"  # "agent" (phone) | "controller" (desktop)
    label: str = "client"
    device_id: Optional[str] = None
    info: Optional[DeviceInfo] = None
    pairing_code: Optional[str] = None
    auto_approve: bool = False
    peer_conn_id: Optional[str] = None
    state: str = "idle"  # idle | awaiting_approval | paired
    connected_at: float = field(default_factory=time.time)

    async def send(self, msg_type: str, **payload: Any) -> None:
        """Send one envelope. Never raises: a dead socket must not kill a loop."""
        try:
            await self.ws.send_json({"type": msg_type, "payload": payload})
        except Exception:
            log.debug("send to %s failed", self.conn_id, exc_info=True)

    async def relay_envelope(self, msg_type: str, payload: Dict[str, Any]) -> bool:
        try:
            await self.ws.send_json({"type": msg_type, "payload": payload})
            return True
        except Exception:
            log.debug("relay to %s failed", self.conn_id, exc_info=True)
            return False

    async def send_bytes(self, data: bytes) -> bool:
        """Forward one binary frame. The hub never inspects or re-frames it."""
        try:
            await self.ws.send_bytes(data)
            return True
        except Exception:
            log.debug("binary relay to %s failed", self.conn_id, exc_info=True)
            return False


class Hub:
    """Tracks every connected agent and controller, and pairs them up."""

    def __init__(self) -> None:
        self._by_conn_id: Dict[str, Connection] = {}
        self._agents: Dict[str, Connection] = {}  # device_id -> agent
        self._controllers: Dict[str, Connection] = {}  # conn_id -> controller
        self._codes: Dict[str, str] = {}  # pairing code -> device_id
        self._pending: Dict[str, str] = {}  # device_id -> controller conn_id
        self._lock = asyncio.Lock()

        # Offline devices loaded from database: device_id -> DeviceInfo
        self._db_devices: Dict[str, DeviceInfo] = {}

        # Supabase client
        url = os.getenv("SUPABASE_URL")
        key = os.getenv("SUPABASE_KEY")
        if url and key:
            self.supabase: Optional[Client] = create_client(url, key)
            self._load_from_db()
        else:
            log.warning("Supabase credentials not found in .env; database persistence disabled")
            self.supabase = None

    def _load_from_db(self) -> None:
        """Fetch known devices from Supabase on startup."""
        if not self.supabase:
            return
        try:
            response = self.supabase.table("devices").select("*").execute()
            for row in response.data:
                device_id = row.get("device_id")
                if not device_id:
                    continue
                info = DeviceInfo(
                    device_id=device_id,
                    name=row.get("name"),
                    model=row.get("model"),
                    manufacturer=row.get("manufacturer"),
                    android_version=row.get("android_version"),
                    sdk_int=row.get("sdk_int"),
                    screen_width=row.get("screen_width"),
                    screen_height=row.get("screen_height"),
                    battery_percent=row.get("battery_percent", -1),
                    can_capture=row.get("can_capture", False),
                    can_inject_input=row.get("can_inject_input", False),
                    installed_app_count=row.get("installed_app_count", 0),
                )
                self._db_devices[device_id] = info
                # If a pairing code was stored, remember it so it stays stable
                code = row.get("pairing_code")
                if code:
                    self._codes[code] = device_id
            log.info("loaded %d devices from database", len(self._db_devices))
        except Exception as e:
            log.error("failed to load devices from Supabase: %s", e)

    async def _upsert_device_in_db(self, conn: Connection) -> None:
        """Save a device's current state to Supabase."""
        if not self.supabase or not conn.device_id:
            return
        info = conn.info
        data = {
            "device_id": conn.device_id,
            "name": info.name if info else None,
            "model": info.model if info else None,
            "manufacturer": info.manufacturer if info else None,
            "android_version": info.android_version if info else None,
            "sdk_int": info.sdk_int if info else None,
            "screen_width": info.screen_width if info else None,
            "screen_height": info.screen_height if info else None,
            "battery_percent": info.battery_percent if info else None,
            "can_capture": info.can_capture if info else None,
            "can_inject_input": info.can_inject_input if info else None,
            "installed_app_count": info.installed_app_count if info else None,
            "pairing_code": conn.pairing_code,
            "auto_approve": conn.auto_approve,
            "last_ip": conn.ws.client.host if conn.ws.client else None,
            "last_seen": "now()",
        }
        try:
            # Upsert by primary key (device_id)
            self.supabase.table("devices").upsert(data).execute()
        except Exception as e:
            log.error("failed to upsert device %s in Supabase: %s", conn.device_id, e)

    async def _save_apps_in_db(self, device_id: str, apps: List[Dict[str, Any]]) -> None:
        """Replace the stored app list for a device."""
        if not self.supabase:
            return
        try:
            # Delete old apps first
            self.supabase.table("device_apps").delete().eq("device_id", device_id).execute()
            
            # Insert new ones in batches
            if not apps:
                return
                
            data = [
                {
                    "device_id": device_id,
                    "package_name": a.get("package"),
                    "label": a.get("label"),
                    "is_system": a.get("system", False),
                }
                for a in apps
            ]
            self.supabase.table("device_apps").insert(data).execute()
            log.info("saved %d apps for device %s to database", len(apps), device_id)
        except Exception as e:
            log.error("failed to save apps for device %s in Supabase: %s", device_id, e)

    # ---------------------------------------------------------------- register

    async def add_agent(
        self,
        conn: Connection,
        info: DeviceInfo,
        requested_code: Optional[str],
        auto_approve: bool,
    ) -> str:
        """Register a phone. Returns the pairing code it should display."""
        code = self._claim_code(requested_code)
        conn.role = "agent"
        conn.label = info.name or info.model or info.device_id
        conn.device_id = info.device_id
        conn.info = info
        conn.pairing_code = code
        conn.auto_approve = auto_approve

        async with self._lock:
            self._by_conn_id[conn.conn_id] = conn
            previous = self._agents.get(info.device_id)
            self._agents[info.device_id] = conn
            self._codes[code] = info.device_id

        if previous is not None and previous.conn_id != conn.conn_id:
            # Same phone reconnecting: reinstalled, or the network flapped. The
            # identity check inside _teardown stops it evicting the new session.
            await self._teardown(previous, "replaced by a newer session from the same device", announce=False)

        log.info("agent online: %s (%s) code=%s", conn.label, info.device_id, code)
        await self._upsert_device_in_db(conn)
        await self.broadcast_device_list()
        return code

    def _claim_code(self, requested: Optional[str]) -> str:
        """Honour the phone's code if it is free and well formed, else mint one."""
        if requested and len(requested) == 6 and requested.isdigit() and requested not in self._codes:
            return requested
        while True:
            code = f"{secrets.randbelow(1_000_000):06d}"
            if code not in self._codes:
                return code

    async def add_controller(self, conn: Connection) -> None:
        conn.role = "controller"
        conn.label = "desktop"
        async with self._lock:
            self._by_conn_id[conn.conn_id] = conn
            self._controllers[conn.conn_id] = conn
        log.info("controller online: %s", conn.conn_id)

    async def send_device_list(self, controller: Connection) -> None:
        await controller.send(Msg.DEVICE_LIST, devices=self.device_views())

    async def update_device_info(self, conn: Connection, info: DeviceInfo) -> None:
        conn.info = info
        await self._upsert_device_in_db(conn)
        await self.broadcast_device_list()

    # ---------------------------------------------------------------- teardown

    async def remove(self, conn: Connection) -> None:
        await self._teardown(conn, "client disconnected")

    async def _teardown(self, conn: Connection, reason: str, announce: bool = True) -> None:
        async with self._lock:
            self._by_conn_id.pop(conn.conn_id, None)

            if conn.role == "agent" and conn.device_id:
                # Only drop the registry entry if it still points at *this*
                # connection; a reconnect may already have replaced it.
                if self._agents.get(conn.device_id) is conn:
                    del self._agents[conn.device_id]
                if conn.pairing_code:
                    self._codes.pop(conn.pairing_code, None)
                if self._pending.get(conn.device_id) == conn.conn_id:
                    self._pending.pop(conn.device_id, None)
                    conn.state = "idle"

            if conn.role == "controller":
                self._controllers.pop(conn.conn_id, None)
                for device_id, controller_conn_id in list(self._pending.items()):
                    if controller_conn_id == conn.conn_id:
                        del self._pending[device_id]

            peer = self._by_conn_id.get(conn.peer_conn_id) if conn.peer_conn_id else None
            conn.peer_conn_id = None
            conn.state = "idle"

        if peer is not None:
            peer.peer_conn_id = None
            peer.state = "idle"
            await peer.send(Msg.PEER_DISCONNECTED, reason=reason, device_id=conn.device_id)

        if conn.role == "agent" and announce:
            log.info("agent offline: %s (%s) - %s", conn.label, conn.device_id, reason)
            await self.broadcast_device_list()

    # ----------------------------------------------------------------- pairing

    async def request_connection(self, controller: Connection, device_id: str, code: str) -> None:
        """A desktop wants to control a phone. Verifies the code, then asks the phone."""
        agent = self._agents.get(device_id)
        if agent is None:
            await controller.send(Msg.ERROR, reason="device_offline", device_id=device_id)
            return
        if self._codes.get(code) != device_id:
            log.warning("controller %s presented a bad code for %s", controller.conn_id, device_id)
            await controller.send(Msg.ERROR, reason="bad_pairing_code", device_id=device_id)
            return
        if agent.peer_conn_id is not None:
            await controller.send(Msg.ERROR, reason="device_busy", device_id=device_id)
            return

        if agent.auto_approve:
            await self._pair(agent, controller)
            return

        async with self._lock:
            self._pending[device_id] = controller.conn_id
            agent.state = "awaiting_approval"
            controller.state = "awaiting_approval"

        await agent.send(Msg.CONNECT_INCOMING, controller=controller.conn_id)
        await controller.send(Msg.CONNECT_PENDING, device_id=device_id, state="waiting_for_phone")

    async def respond_connection(self, agent: Connection, accepted: bool) -> None:
        """The phone answered the consent prompt."""
        async with self._lock:
            controller_conn_id = self._pending.pop(agent.device_id, None)
            agent.state = "idle"

        controller = self._by_conn_id.get(controller_conn_id) if controller_conn_id else None
        if controller is None:
            return

        if not accepted:
            controller.state = "idle"
            log.info("session to %s declined on the phone", agent.device_id)
            await controller.send(Msg.ERROR, reason="declined_by_phone", device_id=agent.device_id)
            return

        await self._pair(agent, controller)

    async def _pair(self, agent: Connection, controller: Connection) -> None:
        agent.peer_conn_id = controller.conn_id
        controller.peer_conn_id = agent.conn_id
        agent.state = "paired"
        controller.state = "paired"
        log.info("paired %s <-> %s", agent.device_id, controller.conn_id)

        info = agent.info.model_dump() if agent.info else {}
        await agent.send(Msg.PEER_CONNECTED, device_id=agent.device_id, peer_role="controller")
        await controller.send(Msg.PEER_CONNECTED, device_id=agent.device_id, peer_role="agent", info=info)
        await self.broadcast_device_list()

    async def unpair(self, conn: Connection) -> None:
        peer = self.peer_of(conn)
        conn.peer_conn_id = None
        conn.state = "idle"
        if peer is not None:
            peer.peer_conn_id = None
            peer.state = "idle"
            await peer.send(
                Msg.PEER_DISCONNECTED,
                reason="session_closed_by_peer",
                device_id=conn.device_id,
            )
        await self.broadcast_device_list()

    def peer_of(self, conn: Connection) -> Optional[Connection]:
        if conn.peer_conn_id is None:
            return None
        return self._by_conn_id.get(conn.peer_conn_id)

    # ----------------------------------------------------------------- routing

    async def relay(self, sender: Connection, msg_type: str, payload: Dict[str, Any]) -> None:
        """Forward an opaque control frame to the sender's paired peer."""
        
        # Intercept app list events to store them in the database
        if msg_type == Msg.EVT_APPS and sender.role == "agent" and sender.device_id:
            apps = payload.get("apps")
            if isinstance(apps, list):
                asyncio.create_task(self._save_apps_in_db(sender.device_id, apps))

        peer = self.peer_of(sender)
        if peer is None:
            await sender.send(Msg.ERROR, reason="not_paired", original_type=msg_type)
            return
        if not await peer.relay_envelope(msg_type, payload):
            await self._teardown(peer, "peer socket failed while relaying")
            await sender.send(Msg.PEER_DISCONNECTED, reason="peer_unreachable")

    async def relay_binary(self, sender: Connection, data: bytes) -> None:
        """Forward a screen frame to the paired peer, untouched.

        Deliberately silent when unpaired: a phone whose desktop just vanished
        would otherwise spam an error per frame, and the controller already
        knows the session ended.
        """
        peer = self.peer_of(sender)
        if peer is None:
            return
        if not await peer.send_bytes(data):
            await self._teardown(peer, "peer socket failed while relaying a frame")
            await sender.send(Msg.PEER_DISCONNECTED, reason="peer_unreachable")

    # ------------------------------------------------------------------ queries

    def device_views(self) -> List[Dict[str, Any]]:
        # Start with all known devices from the database (offline by default)
        views = {
            device_id: {
                "device_id": device_id,
                "info": info.model_dump(),
                "online": False,
                "paired": False,
            }
            for device_id, info in self._db_devices.items()
        }

        # Overlay with currently online agents
        for device_id, agent in self._agents.items():
            views[device_id] = {
                "device_id": device_id,
                "info": agent.info.model_dump() if agent.info else views.get(device_id, {}).get("info", {}),
                "online": True,
                "paired": agent.peer_conn_id is not None,
            }

        return list(views.values())

    async def broadcast_device_list(self) -> None:
        devices = self.device_views()
        for controller in list(self._controllers.values()):
            await controller.send(Msg.DEVICE_LIST, devices=devices)

    def agent_count(self) -> int:
        return len(self._agents)

    def controller_count(self) -> int:
        return len(self._controllers)
