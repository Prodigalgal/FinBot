from __future__ import annotations

import asyncio
import hashlib
import json
from collections.abc import AsyncIterator, Callable
from datetime import datetime, timezone
from typing import Any

from fastapi import Request


SSE_HEADERS = {
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


def encode_sse(event: str, payload: dict[str, Any], *, retry_ms: int | None = None) -> str:
    lines = []
    if retry_ms is not None:
        lines.append(f"retry: {max(1000, retry_ms)}")
    lines.append(f"event: {event}")
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), default=str)
    lines.extend(f"data: {line}" for line in data.splitlines() or [""])
    return "\n".join(lines) + "\n\n"


async def snapshot_event_stream(
    request: Request,
    snapshot_factory: Callable[[], dict[str, Any]],
    *,
    event_name: str,
    poll_seconds: float,
    heartbeat_seconds: float = 15.0,
    terminal_statuses: frozenset[str] = frozenset(),
) -> AsyncIterator[str]:
    yield encode_sse("connected", {"at": _now()}, retry_ms=3000)
    last_digest: str | None = None

    while not await request.is_disconnected():
        task = asyncio.create_task(asyncio.to_thread(snapshot_factory))
        while not task.done():
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout=heartbeat_seconds)
            except TimeoutError:
                yield encode_sse("heartbeat", {"at": _now()})
                if await request.is_disconnected():
                    task.cancel()
                    return

        try:
            snapshot = await task
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            yield encode_sse("error", {"at": _now(), "message": _public_error(exc)})
            await _wait_or_disconnect(request, poll_seconds)
            continue

        serialized = json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"), sort_keys=True, default=str)
        digest = hashlib.sha256(serialized.encode("utf-8")).hexdigest()
        if digest != last_digest:
            yield encode_sse(event_name, snapshot)
            last_digest = digest

        status = str(snapshot.get("status") or "").lower()
        if status in terminal_statuses:
            yield encode_sse("complete", snapshot)
            return

        if not await _wait_or_disconnect(request, poll_seconds):
            return
        yield encode_sse("heartbeat", {"at": _now()})


async def _wait_or_disconnect(request: Request, seconds: float) -> bool:
    deadline = asyncio.get_running_loop().time() + max(0.1, seconds)
    while asyncio.get_running_loop().time() < deadline:
        if await request.is_disconnected():
            return False
        await asyncio.sleep(min(0.5, max(0.0, deadline - asyncio.get_running_loop().time())))
    return not await request.is_disconnected()


def _public_error(error: Exception) -> str:
    return f"{type(error).__name__}: 状态暂时不可用，连接将自动重试"


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
