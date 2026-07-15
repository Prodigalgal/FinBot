# FinBot Quant Service

Python 3.13 asynchronous quantitative research boundary. It owns computation only: backtests,
parameter searches, portfolio optimization, statistical analysis and signal evaluation. It has no
exchange credentials and cannot write orders, account balances or the Java ledger.

Signal evaluation supports moving-average crossover, breakout, mean reversion, RSI momentum,
volume-confirmed trend, and a multi-strategy voting ensemble. Every signal run also emits a typed
indicator snapshot covering SMA 20/50 and 50/200 golden/death cross state, MACD 12/26/9, RSI 14,
Bollinger bands, ATR 14, rolling support/resistance, and normalized support/resistance trend slopes.
`GET /internal/v1/capabilities` exposes the versioned catalog to the Java orchestrator. Java persists
the catalog and computed values in the `QUANT_RESULT` artifact so downstream workflow AI nodes can
reference the available methods without receiving exchange credentials.

The canonical contract is `../../contracts/quant-research.openapi.yaml`. Run the contract and stream
tests with:

```powershell
python -m openapi_spec_validator ../../contracts/quant-research.openapi.yaml
python -m ruff check .
python -m mypy --config-file pyproject.toml src tests
pytest
```

The legacy Python runtime lives in a separate archive repository. New research algorithms are ported
behind `ResearchEngine`; this service never imports legacy Web, Store, OMS or exchange modules.
