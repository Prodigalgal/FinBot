from __future__ import annotations

import asyncio


class DuplicateResearchRunError(RuntimeError):
    """Raised when a non-terminal run with the same ID is already registered."""


class ResearchCancellationRegistry:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._runs: dict[str, asyncio.Event] = {}

    async def register(self, research_run_id: str) -> asyncio.Event:
        cancellation = asyncio.Event()
        async with self._lock:
            if research_run_id in self._runs:
                raise DuplicateResearchRunError(research_run_id)
            self._runs[research_run_id] = cancellation
        return cancellation

    async def release(self, research_run_id: str, cancellation: asyncio.Event) -> None:
        async with self._lock:
            if self._runs.get(research_run_id) is cancellation:
                del self._runs[research_run_id]

    async def cancel(self, research_run_id: str) -> bool:
        async with self._lock:
            cancellation = self._runs.get(research_run_id)
            if cancellation is None:
                return False
            cancellation.set()
            return True
