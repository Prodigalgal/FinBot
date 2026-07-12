from __future__ import annotations

import argparse
import json
from pathlib import Path

import httpx

from finbot.config.settings import Settings
from finbot.network.proxy_runtime import ProxyRuntime


DEFAULT_TARGETS = {
    "firecrawl": "https://api.firecrawl.dev/v2/search",
    "exchange:binance": "https://data-api.binance.vision/api/v3/time",
    "exchange:bybit": "https://api.bybit.com/v5/market/time",
    "exchange:gate": "https://api.gateio.ws/api/v4/spot/time",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Inspect FinBot proxy routes and local sing-box protocol bridges.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--no-start-bridges", action="store_true")
    parser.add_argument("--smoke", action="store_true", help="Run HTTP smoke through ok proxy decisions.")
    parser.add_argument("--target", action="append", help="route=url, e.g. exchange:binance=https://data-api.binance.vision/api/v3/time")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(args.data_dir))
    settings.ensure_dirs()
    targets = dict(DEFAULT_TARGETS)
    for item in args.target or []:
        route, separator, url = item.partition("=")
        if not separator:
            raise SystemExit(f"Invalid --target value: {item}")
        targets[route.strip()] = url.strip()

    runtime = ProxyRuntime.from_settings(settings, start_bridges=not args.no_start_bridges)
    try:
        payload = runtime.summary()
        payload["targets"] = []
        for route, url in targets.items():
            decision = runtime.router.decide(route, url)
            row = {"route": route, "url": url, "decision": decision.to_dict()}
            if args.smoke and decision.ok and decision.proxy:
                row["smoke"] = _http_smoke(decision.proxy, url)
            payload["targets"].append(row)
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    finally:
        runtime.close()


def _http_smoke(proxy: str, url: str) -> dict:
    try:
        with httpx.Client(proxy=proxy, timeout=20, follow_redirects=False) as client:
            response = client.get(url)
        return {
            "status": "reachable",
            "http_status": response.status_code,
            "http_version": response.http_version,
        }
    except httpx.HTTPError as exc:
        return {
            "status": "failed",
            "error_type": type(exc).__name__,
        }


if __name__ == "__main__":
    main()
