from __future__ import annotations

import pytest

from finbot_browser_worker.settings import BrowserWorkerSettings


def test_requires_proxy_url(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("FINBOT_BROWSER_WORKER_PROXY_URL", raising=False)

    with pytest.raises(RuntimeError, match="FINBOT_BROWSER_WORKER_PROXY_URL is required"):
        BrowserWorkerSettings.from_environment()


@pytest.mark.parametrize(
    "proxy_url",
    [
        "direct://internet",
        "http://user:secret@proxy.internal:8080",
        "http://proxy.internal:8080/path",
        "http://proxy.internal:invalid",
    ],
)
def test_rejects_unsafe_proxy_urls(proxy_url: str) -> None:
    with pytest.raises(ValueError):
        BrowserWorkerSettings(proxy_url=proxy_url)


def test_reads_bounded_capacity_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FINBOT_BROWSER_WORKER_PROXY_URL", "http://proxy.internal:8080/")
    monkeypatch.setenv("FINBOT_BROWSER_WORKER_MAX_CONCURRENT_SOLVES", "3")
    monkeypatch.setenv("FINBOT_BROWSER_WORKER_ACQUIRE_TIMEOUT_MS", "750")

    settings = BrowserWorkerSettings.from_environment()

    assert settings.proxy_url == "http://proxy.internal:8080"
    assert settings.proxy_origin == "http://proxy.internal:8080"
    assert settings.maximum_concurrent_solves == 3
    assert settings.acquire_timeout_ms == 750
