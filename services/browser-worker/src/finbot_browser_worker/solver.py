from __future__ import annotations

import asyncio
import logging
from typing import Any

from playwright.async_api import Browser, Playwright, async_playwright

from finbot_browser_worker.models import ChallengeSolveRequest, ChallengeSolveResponse

LOGGER = logging.getLogger(__name__)

_CHALLENGE_HINTS = (
    ("cloudflare", ("cf-browser-verification", "just a moment", "cf-turnstile", "challenge-platform")),
    ("anubis", ("anubis", "proof-of-work", "withanubis")),
    ("recaptcha", ("recaptcha", "grecaptcha")),
    ("hcaptcha", ("hcaptcha", "h-captcha")),
    ("datadome", ("datadome", "captcha-delivery")),
    ("perimeterx", ("perimeterx", "px-captcha", "_px")),
)


class BrowserChallengeSolver:
    def __init__(self) -> None:
        self._playwright: Playwright | None = None
        self._browser: Browser | None = None
        self._lock = asyncio.Lock()

    async def start(self) -> None:
        async with self._lock:
            if self._browser is not None:
                return
            self._playwright = await async_playwright().start()
            self._browser = await self._playwright.chromium.launch(
                headless=True,
                args=[
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-gpu",
                    "--disable-extensions",
                    "--disable-background-networking",
                ],
            )
            LOGGER.info("chromium browser launched")

    async def stop(self) -> None:
        async with self._lock:
            if self._browser is not None:
                await self._browser.close()
                self._browser = None
            if self._playwright is not None:
                await self._playwright.stop()
                self._playwright = None

    @property
    def ready(self) -> bool:
        return self._browser is not None and self._browser.is_connected()

    async def solve(self, request: ChallengeSolveRequest) -> ChallengeSolveResponse:
        if self._browser is None or not self._browser.is_connected():
            await self.start()
        assert self._browser is not None

        context_options: dict[str, Any] = {
            "ignore_https_errors": False,
            "java_script_enabled": True,
            "viewport": {"width": 1365, "height": 900},
        }
        if request.user_agent:
            context_options["user_agent"] = request.user_agent

        context = await self._browser.new_context(**context_options)
        if request.extra_headers:
            await context.set_extra_http_headers(
                {key: value for key, value in request.extra_headers.items() if key and value}
            )
        page = await context.new_page()
        try:
            response = await page.goto(
                str(request.url),
                wait_until=request.wait_until,  # type: ignore[arg-type]
                timeout=request.timeout_ms,
            )
            if request.wait_ms > 0:
                await page.wait_for_timeout(request.wait_ms)
            # Give common challenge scripts a second settle window when still present.
            body_text = ""
            try:
                body_text = (await page.content()).lower()
            except Exception:  # noqa: BLE001 - best-effort HTML snapshot
                body_text = ""
            if any(token in body_text for token in ("just a moment", "cf-turnstile", "challenge", "anubis")):
                await page.wait_for_timeout(min(8_000, max(1_000, request.wait_ms)))
                try:
                    body_text = (await page.content()).lower()
                except Exception:  # noqa: BLE001
                    pass

            cookies_raw = await context.cookies()
            cookies = {
                item["name"]: item["value"]
                for item in cookies_raw
                if item.get("name") and item.get("value") is not None
            }
            title = None
            try:
                title = await page.title()
            except Exception:  # noqa: BLE001
                title = None
            user_agent = request.user_agent or await page.evaluate("() => navigator.userAgent")
            hints = [
                name
                for name, needles in _CHALLENGE_HINTS
                if any(needle in body_text for needle in needles)
            ]
            status = response.status if response is not None else None
            return ChallengeSolveResponse(
                final_url=page.url,
                status_code=status,
                cookies=cookies,
                user_agent=str(user_agent),
                title=title,
                challenge_hints=hints,
                detail="playwright-chromium",
            )
        finally:
            await context.close()
