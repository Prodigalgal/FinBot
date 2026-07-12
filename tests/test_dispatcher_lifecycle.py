from __future__ import annotations

import unittest
from unittest.mock import patch

from finbot.ingestion.dispatcher import Dispatcher
from finbot.ingestion.models import FetchJob, SourceConfig


class _ClosableAdapter:
    instances: list["_ClosableAdapter"] = []

    def __init__(self, **kwargs):
        self.close_calls = 0
        self.__class__.instances.append(self)

    def close(self) -> None:
        self.close_calls += 1


class DispatcherLifecycleTests(unittest.TestCase):
    def setUp(self) -> None:
        _ClosableAdapter.instances.clear()

    def test_firecrawl_modes_share_one_adapter_and_close_once(self) -> None:
        dispatcher = Dispatcher(settings=object(), evidence_store=object())
        search_source = _source("firecrawl_search")
        scrape_job = FetchJob(
            source_id=search_source.id,
            mode=search_source.mode,
            priority="P1",
            job_type="firecrawl_scrape",
            url="https://example.com",
        )

        with patch("finbot.ingestion.dispatcher.FirecrawlAdapter", _ClosableAdapter):
            search_adapter = dispatcher._adapter_for(search_source)
            scrape_adapter = dispatcher._adapter_for(search_source, scrape_job)

        self.assertIs(search_adapter, scrape_adapter)
        self.assertEqual(len(_ClosableAdapter.instances), 1)

        dispatcher.close()
        dispatcher.close()
        self.assertEqual(_ClosableAdapter.instances[0].close_calls, 1)

    def test_closed_dispatcher_rejects_new_adapter_creation(self) -> None:
        dispatcher = Dispatcher(settings=object(), evidence_store=object())
        dispatcher.close()

        with self.assertRaisesRegex(RuntimeError, "Dispatcher is closed"):
            dispatcher._adapter_for(_source("firecrawl_search"))


def _source(mode: str) -> SourceConfig:
    return SourceConfig(
        id=f"source-{mode}",
        tier="tier-1",
        category="news",
        mode=mode,
    )


if __name__ == "__main__":
    unittest.main()
