from __future__ import annotations

from finbot.ingestion.adapters.base import BaseAdapter
from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig
from finbot.market.public_exchanges import PublicExchangeMarketDataClient, supported_providers


class ExchangePublicAPIAdapter(BaseAdapter):
    mode = "exchange_public_api"

    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        provider = source.provider or job.provider
        if provider not in supported_providers():
            return AdapterResult(
                source_id=source.id,
                status="blocked-by-provider",
                detail=f"Unsupported exchange provider: {provider}",
            )

        symbols = job.asset_scope or source.asset_scope or ["BTCUSDT"]
        market_type = "linear" if provider == "bybit" and "funding" in source.data_types else "spot"
        client = PublicExchangeMarketDataClient(
            timeout_seconds=self.timeout_seconds,
            user_agent=self.settings.http_user_agent,
        )
        request_payload = {
            "provider": provider,
            "symbols": symbols,
            "market_type": market_type,
            "data_types": source.data_types,
        }
        request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", request_payload)

        try:
            quotes = []
            for symbol in symbols:
                quote = await client.fetch_quote(provider=provider, symbol=symbol, market_type=market_type)
                quotes.append(quote.to_dict())
            response_payload = {
                "provider": provider,
                "market_type": market_type,
                "quotes": quotes,
            }
            response_path = self.evidence_store.save_json(source.id, f"{job.job_id}.response", response_payload)
            evidence = RawEvidence(
                source_id=source.id,
                job_id=job.job_id,
                url=quotes[0]["source_url"] if quotes else None,
                status_code=200,
                success=True,
                request_path=request_path,
                response_path=response_path,
                metadata={"provider": provider, "market_type": market_type, "symbols": symbols},
            )
            return AdapterResult(
                source_id=source.id,
                status="smoke-tested",
                detail=f"Fetched {len(quotes)} public market quote(s)",
                success=True,
                evidence=evidence,
            )
        except Exception as exc:
            return self.failed(source, job, f"Exchange public API failed: {exc!r}")
