from __future__ import annotations

import hashlib
import json
import math
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol
from urllib.parse import urlparse

import httpx

from finbot_quant.models import ArtifactReference, Exchange, Instrument, MarketType

MAXIMUM_ARTIFACT_BYTES = 64 * 1024 * 1024


class MarketDataArtifactError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class Candle:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float
    funding_rate: float = 0.0

    def __post_init__(self) -> None:
        if self.timestamp.tzinfo is None:
            raise MarketDataArtifactError("candle timestamp must include a timezone")
        prices = (self.open, self.high, self.low, self.close)
        if any(not math.isfinite(value) or value <= 0 for value in prices):
            raise MarketDataArtifactError("candle prices must be positive")
        if self.high < max(self.open, self.close, self.low):
            raise MarketDataArtifactError("candle high is inconsistent")
        if self.low > min(self.open, self.close, self.high):
            raise MarketDataArtifactError("candle low is inconsistent")
        if not math.isfinite(self.volume) or self.volume < 0:
            raise MarketDataArtifactError("candle volume must not be negative")
        if not math.isfinite(self.funding_rate):
            raise MarketDataArtifactError("candle funding rate must be finite")


@dataclass(frozen=True, slots=True)
class InstrumentSeries:
    instrument: Instrument
    candles: tuple[Candle, ...]


@dataclass(frozen=True, slots=True)
class MarketDataSet:
    schema_version: int
    series: tuple[InstrumentSeries, ...]


class ArtifactLoader(Protocol):
    async def load(self, artifact: ArtifactReference) -> bytes: ...


class HttpArtifactLoader:
    def __init__(
        self,
        *,
        service_token: str,
        allowed_http_hosts: frozenset[str],
        timeout_seconds: float = 30.0,
    ) -> None:
        if len(service_token) < 16:
            raise ValueError("artifact service_token must contain at least 16 characters")
        if not allowed_http_hosts:
            raise ValueError("allowed_http_hosts must not be empty")
        self._service_token = service_token
        self._allowed_http_hosts = allowed_http_hosts
        self._timeout_seconds = timeout_seconds

    async def load(self, artifact: ArtifactReference) -> bytes:
        parsed = urlparse(artifact.uri)
        if parsed.scheme in {"http", "https"}:
            return await self._load_http(artifact, parsed.hostname)
        raise MarketDataArtifactError("unsupported market data artifact URI scheme")

    async def _load_http(self, artifact: ArtifactReference, host: str | None) -> bytes:
        if host not in self._allowed_http_hosts:
            raise MarketDataArtifactError("market data artifact host is not allowlisted")
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout_seconds,
                follow_redirects=False,
            ) as client:
                async with client.stream(
                    "GET",
                    artifact.uri,
                    headers={"Authorization": f"Bearer {self._service_token}"},
                ) as response:
                    if response.status_code != 200:
                        raise MarketDataArtifactError(
                            f"market data artifact endpoint returned HTTP {response.status_code}"
                        )
                    content_length = response.headers.get("Content-Length")
                    if content_length is not None and int(content_length) > MAXIMUM_ARTIFACT_BYTES:
                        raise MarketDataArtifactError(
                            "market data artifact exceeds the safety limit"
                        )
                    payload = bytearray()
                    async for chunk in response.aiter_bytes():
                        payload.extend(chunk)
                        if len(payload) > MAXIMUM_ARTIFACT_BYTES:
                            raise MarketDataArtifactError(
                                "market data artifact exceeds the safety limit"
                            )
        except (httpx.HTTPError, ValueError) as exc:
            if isinstance(exc, MarketDataArtifactError):
                raise
            raise MarketDataArtifactError("market data artifact endpoint is unavailable") from exc
        return _verify_artifact(bytes(payload), artifact)


def parse_market_data(payload: bytes) -> MarketDataSet:
    if len(payload) > MAXIMUM_ARTIFACT_BYTES:
        raise MarketDataArtifactError("market data artifact exceeds the safety limit")
    try:
        root = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise MarketDataArtifactError("market data artifact is not valid UTF-8 JSON") from exc
    if not isinstance(root, dict) or set(root) != {"schemaVersion", "instruments"}:
        raise MarketDataArtifactError("market data artifact root has an invalid shape")
    if root["schemaVersion"] != 1 or not isinstance(root["instruments"], list):
        raise MarketDataArtifactError("market data artifact schema version is unsupported")
    series = tuple(_instrument_series(value) for value in root["instruments"])
    if not series:
        raise MarketDataArtifactError("market data artifact contains no instruments")
    return MarketDataSet(schema_version=1, series=series)


def _instrument_series(value: object) -> InstrumentSeries:
    if not isinstance(value, dict) or set(value) != {
        "exchange",
        "symbol",
        "marketType",
        "quoteCurrency",
        "candles",
    }:
        raise MarketDataArtifactError("instrument series has an invalid shape")
    if not all(
        isinstance(value[field], str)
        for field in ("exchange", "symbol", "marketType", "quoteCurrency")
    ):
        raise MarketDataArtifactError("instrument series identity is invalid")
    try:
        instrument = Instrument(
            exchange=Exchange(value["exchange"]),
            symbol=value["symbol"],
            market_type=MarketType(value["marketType"]),
            quote_currency=value["quoteCurrency"],
        )
    except ValueError as exc:
        raise MarketDataArtifactError("instrument series identity is invalid") from exc
    raw_candles = value["candles"]
    if not isinstance(raw_candles, list):
        raise MarketDataArtifactError("instrument candles must be an array")
    candles = tuple(_candle(item) for item in raw_candles)
    if len(candles) < 2:
        raise MarketDataArtifactError("instrument series requires at least two candles")
    ordered = tuple(sorted(candles, key=lambda item: item.timestamp))
    if len({item.timestamp for item in ordered}) != len(ordered):
        raise MarketDataArtifactError("instrument series contains duplicate timestamps")
    return InstrumentSeries(instrument=instrument, candles=ordered)


def _candle(value: object) -> Candle:
    if not isinstance(value, dict):
        raise MarketDataArtifactError("candle must be an object")
    allowed = {"timestamp", "open", "high", "low", "close", "volume", "fundingRate"}
    required = {"timestamp", "open", "high", "low", "close", "volume"}
    if not required.issubset(value) or not set(value).issubset(allowed):
        raise MarketDataArtifactError("candle has an invalid shape")
    if not isinstance(value["timestamp"], str):
        raise MarketDataArtifactError("candle timestamp must be a string")
    try:
        timestamp = datetime.fromisoformat(value["timestamp"].replace("Z", "+00:00"))
        return Candle(
            timestamp=timestamp,
            open=_number(value["open"], "open"),
            high=_number(value["high"], "high"),
            low=_number(value["low"], "low"),
            close=_number(value["close"], "close"),
            volume=_number(value["volume"], "volume"),
            funding_rate=_number(value.get("fundingRate", 0.0), "fundingRate"),
        )
    except MarketDataArtifactError:
        raise
    except (TypeError, ValueError) as exc:
        raise MarketDataArtifactError("candle contains an invalid value") from exc


def _number(value: object, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise MarketDataArtifactError(f"candle {field} must be a JSON number")
    number = float(value)
    if not math.isfinite(number):
        raise MarketDataArtifactError(f"candle {field} must be finite")
    return number


def _verify_artifact(payload: bytes, artifact: ArtifactReference) -> bytes:
    if len(payload) > MAXIMUM_ARTIFACT_BYTES or len(payload) != artifact.byte_size:
        raise MarketDataArtifactError("market data artifact byte size does not match")
    digest = hashlib.sha256(payload).hexdigest()
    if digest != artifact.sha256_hex:
        raise MarketDataArtifactError("market data artifact checksum does not match")
    return payload
