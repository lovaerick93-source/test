"""Wire protocol shared by the phone agent, the desktop controller and this hub.

Every frame on the WebSocket is a JSON envelope::

    {"type": "<message type>", "payload": { ... }}

Hub-control messages (``hello``, ``ping``, ``connect.*``) are consumed or
produced by the hub itself.  Everything else is relayed verbatim between a
paired agent and controller, so the hub never needs to understand it.
"""

from __future__ import annotations

from typing import Dict

from pydantic import BaseModel

# Bumped whenever an incompatible change lands, so an old client is rejected
# with a clear message instead of failing in a confusing way mid-session.
PROTOCOL_VERSION = 1


class Hello(BaseModel):
    """First message every client sends after the socket opens."""

    role: str  # "agent" (phone) | "controller" (desktop)
    protocol_version: int = PROTOCOL_VERSION
    device_id: str | None = None  # agents only; controllers leave this empty
    pairing_code: str | None = None  # agents only; shown on the phone screen
    auto_approve: bool = False  # agents only; skips the per-session prompt


class DeviceInfo(BaseModel):
    """What the phone reports about itself once it is online."""

    device_id: str
    name: str = "unknown"
    model: str = "unknown"
    manufacturer: str = "unknown"
    android_version: str = "unknown"
    sdk_int: int = 0
    screen_width: int = 0
    screen_height: int = 0
    battery_percent: int = -1
    can_capture: bool = False
    can_inject_input: bool = False
    installed_app_count: int = 0


class AppEntry(BaseModel):
    """One installed application, as shown in the desktop's app drawer."""

    package_name: str
    label: str
    system: bool = False


class DeviceView(BaseModel):
    """A device as the controller sees it in the picker."""

    device_id: str
    info: DeviceInfo
    online: bool = True
    paired: bool = False


class Msg:
    """Message type constants. Plain strings keep the wire format readable."""

    # ---- client -> hub
    HELLO = "hello"
    PING = "ping"
    DEVICE_INFO = "device.info"
    CONNECT_REQUEST = "connect.request"
    CONNECT_RESPONSE = "connect.response"
    DISCONNECT = "disconnect"

    # ---- hub -> client
    WELCOME = "welcome"
    PONG = "pong"
    ERROR = "error"
    DEVICE_LIST = "device.list"
    DEVICE_ONLINE = "device.online"
    DEVICE_OFFLINE = "device.offline"
    PEER_CONNECTED = "peer.connected"
    PEER_DISCONNECTED = "peer.disconnected"
    CONNECT_INCOMING = "connect.incoming"
    CONNECT_PENDING = "connect.pending"

    # ---- controller -> agent (relayed verbatim, namespaced so the hub can skip them)
    CTL_PREFIX = "ctl."
    CTL_TAP = "ctl.tap"
    CTL_SWIPE = "ctl.swipe"
    CTL_TEXT = "ctl.text"
    CTL_KEY = "ctl.key"
    CTL_LIST_APPS = "ctl.list_apps"
    CTL_LAUNCH_APP = "ctl.launch_app"
    CTL_START_MIRROR = "ctl.start_mirror"
    CTL_STOP_MIRROR = "ctl.stop_mirror"
    CTL_FILE_PUSH = "ctl.file_push"
    CTL_FILE_PULL = "ctl.file_pull"

    # ---- agent -> controller (relayed verbatim)
    EVT_PREFIX = "evt."
    EVT_APPS = "evt.apps"
    EVT_SESSION = "evt.session"
    EVT_MIRROR_STATE = "evt.mirror_state"
    EVT_FILE_PROGRESS = "evt.file_progress"
    EVT_FILE_DATA = "evt.file_data"
    EVT_CLIPBOARD = "evt.clipboard"

    #: Types the hub handles itself and must never relay.
    HUB_LOCAL = frozenset(
        {
            HELLO,
            PING,
            CONNECT_REQUEST,
            CONNECT_RESPONSE,
            DISCONNECT,
            DEVICE_INFO,
        }
    )


def envelope(msg_type: str, **payload: object) -> Dict[str, object]:
    """Build one wire frame."""
    return {"type": msg_type, "payload": payload}


def device_view(info: DeviceInfo, *, paired: bool = False) -> DeviceView:
    return DeviceView(device_id=info.device_id, info=info, online=True, paired=paired)


#: Coordinates sent by the controller are absolute *device* pixels in the
#: captured frame's own resolution -- never screen fractions.  The phone then
#: needs no maths to inject them, and the desktop scales mouse position by
#: (device_px / viewport_px) on its side.
COORDINATE_SPACE = "device_pixels"
