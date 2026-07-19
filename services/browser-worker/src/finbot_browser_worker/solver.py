from __future__ import annotations

import asyncio
import logging
from typing import Any

from playwright.async_api import Browser, Page, Playwright, async_playwright

from finbot_browser_worker.models import ChallengeSolveRequest, ChallengeSolveResponse

LOGGER = logging.getLogger(__name__)

_CHALLENGE_HINTS = (
    ("cloudflare", ("cf-browser-verification", "just a moment", "cf-turnstile", "challenge-platform")),
    ("anubis", ("anubis", "proof-of-work", "withanubis", "oh noes", "not a bot", "/.within.website/", "xess.min.css")),
    ("recaptcha", ("recaptcha", "grecaptcha")),
    ("hcaptcha", ("hcaptcha", "h-captcha")),
    ("datadome", ("datadome", "captcha-delivery")),
    ("perimeterx", ("perimeterx", "px-captcha", "_px")),
)

_STILL_CHALLENGED_TITLES = (
    "oh noes",
    "not a bot",
    "just a moment",
    "attention required",
    "captcha",
    "verify you are human",
    "checking your browser",
)

_STEALTH_INIT = """
Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'] });
Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
window.chrome = window.chrome || { runtime: {} };
const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
if (originalQuery) {
  window.navigator.permissions.query = (parameters) => (
    parameters && parameters.name === 'notifications'
      ? Promise.resolve({ state: Notification.permission })
      : originalQuery(parameters)
  );
}
"""


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
                    "--disable-blink-features=AutomationControlled",
                    "--disable-features=IsolateOrigins,site-per-process",
                    "--disable-extensions",
                    "--disable-background-networking",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--lang=zh-CN,zh,en-US,en",
                ],
            )
            LOGGER.info("chromium browser launched (stealth args enabled)")

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

        user_agent = request.user_agent or (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )
        context_options: dict[str, Any] = {
            "ignore_https_errors": False,
            "java_script_enabled": True,
            "viewport": {"width": 1366, "height": 864},
            "locale": "zh-CN",
            "timezone_id": "Asia/Shanghai",
            "color_scheme": "light",
            "user_agent": user_agent,
            "extra_http_headers": {
                "Accept-Language": "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
                "Upgrade-Insecure-Requests": "1",
                "Sec-CH-UA": '"Google Chrome";v="131", "Chromium";v="131", "Not_A Brand";v="24"',
                "Sec-CH-UA-Mobile": "?0",
                "Sec-CH-UA-Platform": '"Windows"',
            },
        }
        if request.extra_headers:
            merged = dict(context_options["extra_http_headers"])
            merged.update({key: value for key, value in request.extra_headers.items() if key and value})
            context_options["extra_http_headers"] = merged

        context = await self._browser.new_context(**context_options)
        await context.add_init_script(_STEALTH_INIT)
        page = await context.new_page()
        try:
            response = await page.goto(
                str(request.url),
                wait_until=request.wait_until,  # type: ignore[arg-type]
                timeout=request.timeout_ms,
            )
            await self._await_challenge_progress(page, request)
            body_text = await self._safe_content(page)
            title = await self._safe_title(page)
            hints = self._hints(body_text, title)
            cookies_raw = await context.cookies()
            cookies = {
                item["name"]: item["value"]
                for item in cookies_raw
                if item.get("name") and item.get("value") is not None
            }
            still_challenged = self._still_challenged(title, body_text, hints)
            # Prefer non-empty challenge/session cookies; otherwise report explicit failure markers.
            if still_challenged and not cookies:
                detail = "playwright-chromium;challenge-unresolved"
            elif still_challenged:
                detail = "playwright-chromium;challenge-maybe-partial"
            else:
                detail = "playwright-chromium;ok"
            status = response.status if response is not None else None
            # If navigation settled on a challenge page, keep the latest document status when possible.
            if still_challenged and status is not None and status < 400:
                # Many Anubis walls return 200 HTML; surface as unresolved rather than success JSON.
                pass
            return ChallengeSolveResponse(
                final_url=page.url,
                status_code=status,
                cookies=cookies,
                user_agent=str(await page.evaluate("() => navigator.userAgent")),
                title=title,
                challenge_hints=hints + (["unresolved"] if still_challenged else []),
                detail=detail,
            )
        finally:
            await context.close()

    async def _await_challenge_progress(self, page: Page, request: ChallengeSolveRequest) -> None:
        base_wait = max(request.wait_ms, 1_000)
        # Anubis / CF walls need longer settle windows than generic pages.
        extra_deadline_ms = min(request.timeout_ms, max(base_wait, 25_000))
        elapsed = 0
        step = 2_000
        while elapsed < extra_deadline_ms:
            await page.wait_for_timeout(step)
            elapsed += step
            title = (await self._safe_title(page) or "").lower()
            body = await self._safe_content(page)
            if not self._still_challenged(title, body, self._hints(body, title)):
                # Give one short quiet period after challenge title flips.
                await page.wait_for_timeout(min(3_000, step))
                return
            # Try light interaction that some walls require without clicking captcha widgets.
            try:
                await page.mouse.move(120 + (elapsed // 500) % 40, 160 + (elapsed // 700) % 30)
            except Exception:  # noqa: BLE001
                pass

    @staticmethod
    async def _safe_content(page: Page) -> str:
        try:
            return (await page.content()).lower()
        except Exception:  # noqa: BLE001
            return ""

    @staticmethod
    async def _safe_title(page: Page) -> str | None:
        try:
            return await page.title()
        except Exception:  # noqa: BLE001
            return None

    @staticmethod
    def _hints(body_text: str, title: str | None) -> list[str]:
        haystack = f"{body_text}\n{(title or '').lower()}"
        return [
            name
            for name, needles in _CHALLENGE_HINTS
            if any(needle in haystack for needle in needles)
        ]

    @staticmethod
    def _still_challenged(title: str | None, body_text: str, hints: list[str]) -> bool:
        title_l = (title or "").lower()
        if any(token in title_l for token in _STILL_CHALLENGED_TITLES):
            return True
        if "anubis" in hints or "cloudflare" in hints:
            if any(
                token in body_text
                for token in (
                    "oh noes",
                    "not a bot",
                    "just a moment",
                    "proof-of-work",
                    "cf-turnstile",
                    "/.within.website/",
                )
            ):
                return True
        # JSON success body is never a challenge wall.
        stripped = body_text.lstrip()
        if stripped.startswith("{") or stripped.startswith("["):
            return False
        return False
