from __future__ import annotations

import hashlib
import json

import pytest

from finbot_quant.market_data import (
    HttpArtifactLoader,
    MarketDataArtifactError,
    parse_market_data,
)
from finbot_quant.models import ArtifactKind, ArtifactReference


@pytest.mark.asyncio
async def test_http_loader_rejects_non_allowlisted_host() -> None:
    loader = HttpArtifactLoader(
        service_token="s" * 32,
        allowed_http_hosts=frozenset({"finbot-backend"}),
    )

    with pytest.raises(MarketDataArtifactError, match="allowlisted"):
        await loader.load(_artifact(b"{}", "https://unexpected.example/artifact"))


@pytest.mark.asyncio
async def test_http_loader_rejects_file_uri() -> None:
    loader = HttpArtifactLoader(
        service_token="s" * 32,
        allowed_http_hosts=frozenset({"finbot-backend"}),
    )

    with pytest.raises(MarketDataArtifactError, match="unsupported"):
        await loader.load(_artifact(b"{}", "file:///etc/passwd"))


def test_market_data_rejects_string_and_non_finite_numbers() -> None:
    candles = [_candle("1.0"), _candle(1.0)]
    root = {
        "schemaVersion": 2,
        "dataPlane": "LIVE",
        "instruments": [
            {
                "exchange": "GATE",
                "environment": "LIVE",
                "symbol": "BTC_USDT",
                "marketType": "PERPETUAL",
                "quoteCurrency": "USDT",
                "candles": candles,
            }
        ],
    }
    with pytest.raises(MarketDataArtifactError, match="JSON number"):
        parse_market_data(json.dumps(root).encode())

    candles[0]["open"] = float("nan")
    with pytest.raises(MarketDataArtifactError, match="finite"):
        parse_market_data(json.dumps(root).encode())


def _candle(open_price: object) -> dict[str, object]:
    return {
        "timestamp": "2026-01-01T00:00:00+00:00",
        "open": open_price,
        "high": 2.0,
        "low": 0.5,
        "close": 1.5,
        "volume": 10.0,
    }


def _artifact(payload: bytes, uri: str) -> ArtifactReference:
    return ArtifactReference(
        kind=ArtifactKind.INPUT_MARKET_DATA,
        uri=uri,
        sha256_hex=hashlib.sha256(payload).hexdigest(),
        media_type="application/json",
        byte_size=len(payload),
    )
