from __future__ import annotations

import argparse
import asyncio
import json

from finbot.advisory.engine import AdvisoryConfig
from finbot.cli.common import build_store, write_report
from finbot.market.public_exchanges import PublicExchangeMarketDataClient
from finbot.network.proxy_runtime import ProxyRuntime
from finbot.operator.workbench import OperatorWorkbenchBuilder, OperatorWorkbenchConfig, parse_intervals, parse_provider_specs


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build Phase 6 Operator Workbench with public market data and advisory output.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--symbol", action="append", help="Trading symbol, e.g. BTCUSDT. Can be repeated.")
    parser.add_argument("--provider", action="append", help="Provider spec: binance, gate, bybit, or bybit:linear. Can be repeated.")
    parser.add_argument("--data-source", default="live_public", choices=["live_public"])
    parser.add_argument("--execution-mode", default="advisory_only", choices=["advisory_only"])
    parser.add_argument("--interval", action="append", help="Candle interval, e.g. 1h. Can be repeated or comma-separated.")
    parser.add_argument("--candle-limit", type=int, default=60)
    parser.add_argument("--timeout-seconds", type=float, default=20)
    parser.add_argument("--profile", default="phase6-default")
    parser.add_argument("--risk-per-trade-pct", type=float, default=0.5)
    parser.add_argument("--max-position-notional-pct", type=float, default=5.0)
    parser.add_argument("--reward-risk-ratio", type=float, default=1.6)
    parser.add_argument("--no-persist", action="store_true")
    return parser.parse_args()


async def run() -> int:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    proxy_runtime = ProxyRuntime.from_settings(settings)
    try:
        market_client = PublicExchangeMarketDataClient(
            timeout_seconds=args.timeout_seconds,
            user_agent=settings.http_user_agent,
            proxy_router=proxy_runtime.router,
        )
        builder = OperatorWorkbenchBuilder(store=store, market_client=market_client)
        config = OperatorWorkbenchConfig(
            symbols=tuple(args.symbol or OperatorWorkbenchConfig.symbols),
            providers=parse_provider_specs(args.provider),
            data_source=args.data_source,
            execution_mode=args.execution_mode,
            intervals=parse_intervals(args.interval),
            candle_limit=args.candle_limit,
            timeout_seconds=args.timeout_seconds,
            persist=not args.no_persist,
            advisory=AdvisoryConfig(
                profile=args.profile,
                risk_per_trade_pct=args.risk_per_trade_pct,
                max_position_notional_pct=args.max_position_notional_pct,
                reward_risk_ratio=args.reward_risk_ratio,
            ),
        )
        report = await builder.build(config)
        report["proxy_runtime"] = proxy_runtime.summary()
        output = write_report(settings, "operator-workbench-latest.json", report)
    finally:
        proxy_runtime.close()

    print("Report:", report["report_id"])
    print("Status:", report["status"])
    print("Summary:", json.dumps(report["summary"], ensure_ascii=False))
    print("Execution allowed:", report["policy"]["execution_allowed"])
    print("Output:", output)
    return 0 if report["status"] in {"ok", "partial"} else 1


def main() -> None:
    raise SystemExit(asyncio.run(run()))


if __name__ == "__main__":
    main()
