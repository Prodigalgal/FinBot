# FinBot Quant Service

Python 3.13 asynchronous quantitative research boundary. It owns computation only: backtests,
parameter searches, portfolio optimization, statistical analysis and signal evaluation. It has no
exchange credentials and cannot write orders, account balances or the Java ledger.

The canonical contract is `../contracts/quant-research.openapi.yaml`. Run the contract and stream
tests with:

```powershell
python -m openapi_spec_validator ../contracts/quant-research.openapi.yaml
python -m ruff check .
python -m mypy --config-file pyproject.toml src tests
pytest
```

The existing root `finbot/` package is a frozen migration reference. New research algorithms are
ported behind `ResearchEngine`; this service never imports the legacy Web, Store, OMS or exchange
modules.
