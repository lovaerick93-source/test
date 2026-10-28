"""Server-side file transfer between a paired controller and agent.

Handles ``ctl.file_push`` (server files/ -> phone) and ``ctl.file_pull``
(phone <- controller request). The Hub delegates these two message types here
so it stays focused on connection registry, pairing, and generic relay.
"""

from __future__ import annotations

import logging
import os
import secrets
from pathlib import Path
from typing import Any, Dict, Optional, TYPE_CHECKING

import aiofiles

from models import Msg

if TYPE_CHECKING:
    from hub import Connection, Hub

log = logging.getLogger("hub")


class FileTransferManager:
    """Streams files between a paired controller and agent via the hub."""

    CHUNK_SIZE = 64 * 1024

    def __init__(self, hub: Hub, files_dir: Path) -> None:
        self._hub = hub
        self.files_dir = files_dir

    # ------------------------------------------------------------------ push

    async def handle_file_push(self, sender: Connection, payload: Dict[str, Any]) -> None:
        """Stream a file from the server files/ dir to the paired phone in chunks.

        Before sending raw binary chunks, a JSON "start" envelope is relayed to
        the peer so it can distinguish file data from video frames. A "done"
        envelope is sent after the last chunk.
        """
        peer = self._hub.peer_of(sender)
        if peer is None:
            await sender.send(Msg.ERROR, reason="not_paired", original_type=Msg.CTL_FILE_PUSH)
            return
        try:
            filename = payload.get("filename", "unknown")
            file_id = payload.get("file_id", "file")
            file_size = payload.get("file_size", 0)

            # Path traversal protection: strip any directory components, then
            # verify the resolved path stays inside FILES_DIR.
            safe_name = Path(filename).name
            filepath = self.files_dir / safe_name
            try:
                resolved = filepath.resolve()
                files_dir_resolved = self.files_dir.resolve()
                prefix = str(files_dir_resolved) + os.sep
                if not (str(resolved).startswith(prefix) or resolved == files_dir_resolved):
                    await sender.send(Msg.ERROR, reason="invalid_filename", file=filename)
                    return
            except Exception:
                await sender.send(Msg.ERROR, reason="invalid_filename", file=filename)
                return

            if not resolved.exists():
                await sender.send(Msg.ERROR, reason="file_not_found", file=filename)
                return

            # Tell the phone a file transfer is starting so it can tell these
            # binary chunks apart from video frames.
            await peer.relay_envelope(
                Msg.CTL_FILE_PUSH,
                {
                    "file_id": file_id,
                    "filename": safe_name,
                    "file_size": file_size,
                    "action": "start",
                },
            )

            chunk_size = self.CHUNK_SIZE
            bytes_sent = 0
            async with aiofiles.open(resolved, "rb") as f:
                while True:
                    chunk = await f.read(chunk_size)
                    if not chunk:
                        break
                    await peer.send_bytes(chunk)
                    bytes_sent += len(chunk)
                    progress = (bytes_sent / file_size * 100) if file_size > 0 else 100
                    await sender.send(
                        Msg.EVT_FILE_PROGRESS,
                        file_id=file_id,
                        filename=safe_name,
                        status="sending",
                        progress=progress,
                        total=file_size,
                        sent=bytes_sent,
                    )

            # Tell the phone the transfer is complete.
            await peer.relay_envelope(
                Msg.CTL_FILE_PUSH,
                {
                    "file_id": file_id,
                    "filename": safe_name,
                    "file_size": file_size,
                    "action": "done",
                },
            )
            await sender.send(
                Msg.EVT_FILE_PROGRESS,
                file_id=file_id,
                filename=safe_name,
                status="done",
                progress=100,
                total=file_size,
                sent=bytes_sent,
            )
            log.info("File pushed to device %s: %s (%d bytes)", sender.device_id, safe_name, bytes_sent)
        except Exception as e:
            log.error("File push failed: %s", e)
            await sender.send(Msg.ERROR, reason="file_push_failed", error=str(e))

    # ------------------------------------------------------------------ pull

    async def handle_file_pull(self, sender: Connection, payload: Dict[str, Any]) -> None:
        """Send a pull request envelope to the phone with a generated file_id."""
        peer = self._hub.peer_of(sender)
        if peer is None:
            await sender.send(Msg.ERROR, reason="not_paired", original_type=Msg.CTL_FILE_PULL)
            return
        try:
            file_id = payload.get("file_id", secrets.token_hex(8))
            filename = payload.get("filename", "unknown")
            await sender.send(
                Msg.EVT_FILE_PROGRESS,
                file_id=file_id,
                filename=filename,
                status="requesting",
                progress=0,
                total=0,
            )
            await peer.relay_envelope(Msg.CTL_FILE_PULL, {"file_id": file_id, "filename": filename})
            log.info("File pull requested from device %s: %s", sender.device_id, filename)
        except Exception as e:
            log.error("File pull request failed: %s", e)
            await sender.send(Msg.ERROR, reason="file_pull_failed", error=str(e))