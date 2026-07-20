from __future__ import annotations

import asyncio
from typing import cast
from unittest.mock import AsyncMock, MagicMock

import pytest
from playwright.async_api import Browser
from pydantic import HttpUrl

from finbot_browser_worker.models import ChallengeSolveRequest, ChallengeSolveResponse
from finbot_browser_worker.settings import BrowserWorkerSettings
from finbot_browser_worker.solver import BrowserChallengeSolver, BrowserWorkerCapacityError


def request() -> ChallengeSolveRequest:
    return ChallengeSolveRequest(
        url=HttpUrl("https://example.com/"),
        wait_ms=0,
        timeout_ms=5_000,
    )


@pytest.mark.asyncio
async def test_passes_mandatory_proxy_to_browser_context() -> None:
    settings = BrowserWorkerSettings(proxy_url="http://proxy.internal:8080")
    solver = BrowserChallengeSolver(settings)
    browser = MagicMock()
    browser.is_connected.return_value = True
    context = AsyncMock()
    page = AsyncMock()
    response = MagicMock(status=200)
    browser.new_context = AsyncMock(return_value=context)
    context.new_page.return_value = page
    context.cookies.return_value = [{"name": "session", "value": "ok"}]
    page.goto.return_value = response
    page.content.return_value = '{"results": []}'
    page.title.return_value = "Search results"
    page.evaluate.return_value = "browser-ua"
    page.url = "https://example.com/"
    solver._browser = cast(Browser, browser)

    result = await solver.solve(request())

    assert browser.new_context.await_args.kwargs["proxy"] == {
        "server": "http://proxy.internal:8080"
    }
    assert result.cookies == {"session": "ok"}
    assert result.detail == "playwright-chromium;ok"
    context.close.assert_awaited_once()


@pytest.mark.asyncio
async def test_rejects_when_capacity_wait_expires() -> None:
    entered = asyncio.Event()
    release = asyncio.Event()

    class ControlledSolver(BrowserChallengeSolver):
        @property
        def ready(self) -> bool:
            return True

        async def _solve_once(self, value: ChallengeSolveRequest) -> ChallengeSolveResponse:
            entered.set()
            await release.wait()
            return ChallengeSolveResponse(
                final_url=str(value.url),
                status_code=200,
                cookies={"session": "ok"},
                user_agent="test-ua",
                detail="test",
            )

    solver = ControlledSolver(
        BrowserWorkerSettings(
            proxy_url="http://proxy.internal:8080",
            maximum_concurrent_solves=1,
            acquire_timeout_ms=100,
        )
    )
    first = asyncio.create_task(solver.solve(request()))
    await entered.wait()

    with pytest.raises(BrowserWorkerCapacityError):
        await solver.solve(request())

    assert solver.status.active_solves == 1
    assert solver.status.waiting_solves == 0
    assert solver.status.rejected_solves == 1
    release.set()
    await first
    assert solver.status.completed_solves == 1
    assert solver.status.active_solves == 0


@pytest.mark.asyncio
async def test_proxy_failure_fails_closed_without_direct_context_retry() -> None:
    solver = BrowserChallengeSolver(BrowserWorkerSettings(proxy_url="http://proxy.internal:8080"))
    browser = MagicMock()
    browser.is_connected.return_value = True
    browser.new_context = AsyncMock(side_effect=RuntimeError("proxy unavailable"))
    solver._browser = cast(Browser, browser)

    with pytest.raises(RuntimeError, match="proxy unavailable"):
        await solver.solve(request())

    browser.new_context.assert_awaited_once()
    assert browser.new_context.await_args.kwargs["proxy"] == {"server": "http://proxy.internal:8080"}
    assert solver.status.failed_solves == 1


def test_detects_anubis_and_does_not_treat_json_as_challenge() -> None:
    anubis = "<html><title>Oh noes!</title><script src='/.within.website/x/xess.min.js'></script>proof-of-work</html>"

    hints = BrowserChallengeSolver._hints(anubis, "Oh noes!")

    assert "anubis" in hints
    assert BrowserChallengeSolver._still_challenged("Oh noes!", anubis, hints)
    assert BrowserChallengeSolver._still_challenged(
        "Verify you are human",
        "<div class='g-recaptcha'></div>",
        ["recaptcha"],
    )
    assert not BrowserChallengeSolver._still_challenged(
        "Search results",
        '{"results": []}',
        ["cloudflare"],
    )
