from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from finbot.config.runtime_config import RuntimeConfigStore
from finbot.config.settings import Settings
from finbot.exchange.bybit_demo import (
    BYBIT_DEMO_API_BASE,
    BybitDemoApiError,
    BybitDemoClient,
)
from finbot.exchange.gate_testnet import (
    GATE_TESTNET_API_BASE,
    GateTestnetApiError,
    GateTestnetClient,
)
from finbot.network.proxy_runtime import ProxyRuntime


def main() -> int:
    parser = argparse.ArgumentParser(description="只读验证 Gate TestNet 与 Bybit Demo 凭据")
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--no-start-bridges", action="store_true")
    parser.add_argument("--max-routes", type=int, default=8)
    parser.add_argument(
        "--output",
        type=Path,
        default=PROJECT_ROOT / "data" / "reports" / "paper-exchange-credential-probe.json",
    )
    args = parser.parse_args()

    project_root = PROJECT_ROOT
    settings = Settings.from_env(project_root=project_root)
    values = RuntimeConfigStore(project_root).values()
    runtime = ProxyRuntime.from_settings(settings, start_bridges=not args.no_start_bridges)
    try:
        report = {
            "status": "completed",
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "write_requests_sent": 0,
            "targets": {
                "gate_testnet": _probe_target(
                    runtime=runtime,
                    route_name="exchange:gate",
                    target_url=GATE_TESTNET_API_BASE,
                    credentials_configured=_credentials_configured(
                        values,
                        "paper_execution.gate_testnet_api_key",
                        "paper_execution.gate_testnet_api_secret",
                    ),
                    credential_fingerprint=_credential_fingerprint(
                        values.get("paper_execution.gate_testnet_api_key"),
                        values.get("paper_execution.gate_testnet_api_secret"),
                    ),
                    client_factory=lambda proxy: GateTestnetClient(
                        str(values.get("paper_execution.gate_testnet_api_key") or ""),
                        str(values.get("paper_execution.gate_testnet_api_secret") or ""),
                        proxy=proxy,
                        timeout_seconds=15,
                    ),
                    probe=lambda client: client.get_position(
                        "usdt",
                        args.symbol.replace("USDT", "_USDT"),
                    ),
                    max_routes=args.max_routes,
                ),
                "bybit_demo": _probe_target(
                    runtime=runtime,
                    route_name="exchange:bybit",
                    target_url=BYBIT_DEMO_API_BASE,
                    credentials_configured=_credentials_configured(
                        values,
                        "paper_execution.bybit_demo_api_key",
                        "paper_execution.bybit_demo_api_secret",
                    ),
                    credential_fingerprint=_credential_fingerprint(
                        values.get("paper_execution.bybit_demo_api_key"),
                        values.get("paper_execution.bybit_demo_api_secret"),
                    ),
                    client_factory=lambda proxy: BybitDemoClient(
                        str(values.get("paper_execution.bybit_demo_api_key") or ""),
                        str(values.get("paper_execution.bybit_demo_api_secret") or ""),
                        proxy=proxy,
                        timeout_seconds=15,
                    ),
                    probe=lambda client: client.list_positions(args.symbol.replace("_", "")),
                    max_routes=args.max_routes,
                ),
            },
        }
        output_path = args.output if args.output.is_absolute() else PROJECT_ROOT / args.output
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    finally:
        runtime.close()
    return 0


def _probe_target(
    *,
    runtime: ProxyRuntime,
    route_name: str,
    target_url: str,
    credentials_configured: bool,
    credential_fingerprint: str | None,
    client_factory: Callable[[str | None], Any],
    probe: Callable[[Any], Any],
    max_routes: int,
) -> dict[str, Any]:
    if not credentials_configured:
        return {
            "status": "blocked",
            "credentials_configured": False,
            "credential_fingerprint": None,
            "attempts": [],
            "reason": "missing_credentials",
        }

    attempts: list[dict[str, Any]] = []
    seen_routes: set[tuple[str, str]] = set()
    for decision in runtime.router.candidate_decisions(
        route_name,
        target_url,
        attempts=max(1, max_routes),
    ):
        route_key = (decision.status, decision.proxy or "direct")
        if route_key in seen_routes:
            continue
        seen_routes.add(route_key)
        attempt: dict[str, Any] = {
            "route_status": decision.status,
            "proxy": decision.proxy_redacted,
        }
        if not decision.ok:
            attempt.update({"status": "blocked", "reason": decision.reason})
            attempts.append(attempt)
            continue

        client = None
        try:
            client = client_factory(decision.proxy)
            result = probe(client)
            attempt.update(
                {
                    "status": "passed",
                    "result_count": _result_count(result),
                }
            )
        except GateTestnetApiError as exc:
            attempt.update(
                {
                    "status": "failed",
                    "http_status": exc.status_code,
                    "code": exc.label,
                    "message": _safe_message(exc.message),
                }
            )
        except BybitDemoApiError as exc:
            attempt.update(
                {
                    "status": "failed",
                    "http_status": exc.status_code,
                    "code": exc.code,
                    "message": _safe_message(exc.message),
                }
            )
        except Exception as exc:
            attempt.update(
                {
                    "status": "failed",
                    "error_type": type(exc).__name__,
                    "message": _safe_message(str(exc)),
                }
            )
        finally:
            if client is not None:
                client.close()
        attempts.append(attempt)
        if attempt["status"] == "passed":
            break

    status = "passed" if any(item["status"] == "passed" for item in attempts) else "failed"
    return {
        "status": status,
        "credentials_configured": True,
        "credential_fingerprint": credential_fingerprint,
        "attempts": attempts,
    }


def _credentials_configured(values: dict[str, Any], key_name: str, secret_name: str) -> bool:
    return bool(str(values.get(key_name) or "").strip() and str(values.get(secret_name) or "").strip())


def _credential_fingerprint(api_key: Any, api_secret: Any) -> str | None:
    key = str(api_key or "").strip()
    secret = str(api_secret or "").strip()
    if not key or not secret:
        return None
    return hashlib.sha256(f"{key}\0{secret}".encode("utf-8")).hexdigest()[:16]


def _result_count(result: Any) -> int:
    if result is None:
        return 0
    if isinstance(result, (list, tuple, set, dict)):
        return len(result)
    return 1


def _safe_message(value: str) -> str:
    return " ".join(value.split())[:160]


if __name__ == "__main__":
    raise SystemExit(main())
