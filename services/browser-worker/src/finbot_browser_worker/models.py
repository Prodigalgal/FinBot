from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field, HttpUrl


class ChallengeSolveRequest(BaseModel):
    url: HttpUrl
    wait_ms: int = Field(default=5_000, ge=0, le=60_000)
    timeout_ms: int = Field(default=45_000, ge=5_000, le=120_000)
    user_agent: str | None = Field(default=None, max_length=500)
    extra_headers: dict[str, str] = Field(default_factory=dict)
    wait_until: str = Field(default="domcontentloaded", max_length=32)


class ChallengeSolveResponse(BaseModel):
    final_url: str
    status_code: int | None
    cookies: dict[str, str]
    user_agent: str
    title: str | None = None
    challenge_hints: list[str] = Field(default_factory=list)
    detail: str = ""


class HealthResponse(BaseModel):
    status: str
    engine: str
    ready: bool
    detail: dict[str, Any] = Field(default_factory=dict)
