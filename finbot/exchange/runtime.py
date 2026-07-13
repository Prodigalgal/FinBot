from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any

from finbot.cli.common import build_store, write_report
from finbot.exchange.account_activity import AccountActivityQuery, exchange_source
from finbot.exchange.account_snapshot import (
    ExchangeAccountSnapshot,
    PnlWindow,
    account_snapshot_payload,
    blocked_account_snapshot,
    utc_now,
)
from finbot.exchange.bybit_demo import BYBIT_DEMO_API_BASE, BybitDemoAdapter, BybitDemoClient
from finbot.exchange.gate_testnet import GATE_TESTNET_API_BASE, GateTestnetAdapter, GateTestnetClient
from finbot.exchange.paper_execution import MultiExchangePaperExecutionEngine, PaperExecutionPolicy
from finbot.network.proxy_runtime import ProxyRuntime


PAPER_ADAPTER_PROVIDERS = {
    "gate_testnet": "gate",
    "bybit_demo": "bybit",
}
SUPPORTED_PAPER_ADAPTERS = frozenset(PAPER_ADAPTER_PROVIDERS)


def fetch_exchange_accounts(
    *,
    config: Any,
    pnl_window: PnlWindow,
    max_routes: int = 4,
) -> dict[str, Any]:
    generated_at = utc_now()
    enabled_adapters = set(config.paper_execution_adapters)
    descriptors = _exchange_descriptors(config)
    snapshots: dict[str, ExchangeAccountSnapshot] = {}
    pending: list[dict[str, Any]] = []
    for descriptor in descriptors:
        adapter_id = str(descriptor["adapter_id"])
        if adapter_id not in enabled_adapters:
            snapshots[adapter_id] = _unavailable_snapshot(
                descriptor,
                status="disabled",
                error="该模拟交易所未启用",
                fetched_at=generated_at,
            )
        elif not descriptor["api_key"] or not descriptor["api_secret"]:
            snapshots[adapter_id] = _unavailable_snapshot(
                descriptor,
                status="blocked",
                error="缺少模拟环境 API key/secret",
                fetched_at=generated_at,
            )
        else:
            pending.append(descriptor)

    if not pending:
        return account_snapshot_payload(
            (snapshots[str(descriptor["adapter_id"])] for descriptor in descriptors),
            pnl_window=pnl_window,
            generated_at=generated_at,
        )

    settings, _store = build_store(config.data_dir)
    proxy_runtime = None
    try:
        proxy_runtime = ProxyRuntime.from_settings(
            settings,
            start_bridges=bool(config.start_bridges),
            start_firecrawl_bridges=False,
            start_exchange_bridges=bool(config.start_bridges),
        )
        routed: list[tuple[dict[str, Any], list[Any]]] = []
        for descriptor in pending:
            routes = list(
                proxy_runtime.router.candidate_decisions(
                    str(descriptor["route_name"]),
                    str(descriptor["target_url"]),
                    attempts=max(1, min(max_routes, 5)),
                )
            )
            routed.append((descriptor, routes))

        with ThreadPoolExecutor(max_workers=min(2, len(routed)), thread_name_prefix="finbot-account") as executor:
            future_map = {
                executor.submit(_fetch_account_with_routes, descriptor, routes, pnl_window): descriptor
                for descriptor, routes in routed
            }
            for future in as_completed(future_map):
                descriptor = future_map[future]
                adapter_id = str(descriptor["adapter_id"])
                try:
                    snapshots[adapter_id] = future.result()
                except Exception as exc:
                    snapshots[adapter_id] = _unavailable_snapshot(
                        descriptor,
                        status="failed",
                        error=_safe_account_error(exc, descriptor),
                        fetched_at=generated_at,
                    )
    except Exception as exc:
        for descriptor in pending:
            snapshots[str(descriptor["adapter_id"])] = _unavailable_snapshot(
                descriptor,
                status="failed",
                error=f"代理运行时初始化失败：{_safe_account_error(exc, descriptor)}",
                fetched_at=generated_at,
            )
    finally:
        if proxy_runtime is not None:
            proxy_runtime.close()

    return account_snapshot_payload(
        (snapshots[str(descriptor["adapter_id"])] for descriptor in descriptors),
        pnl_window=pnl_window,
        generated_at=generated_at,
    )


def fetch_exchange_account_activity(
    *,
    config: Any,
    query: AccountActivityQuery,
    max_routes: int = 2,
) -> dict[str, Any]:
    selected_adapters = set(query.selected_exchange_adapters)
    descriptors = tuple(
        descriptor
        for descriptor in _exchange_descriptors(config)
        if descriptor["adapter_id"] in selected_adapters
    )
    if not descriptors:
        return {"sources": [], "activities": []}

    enabled_adapters = set(config.paper_execution_adapters)
    sources: list[dict[str, Any]] = []
    activities: list[dict[str, Any]] = []
    pending: list[dict[str, Any]] = []
    for descriptor in descriptors:
        adapter_id = str(descriptor["adapter_id"])
        if adapter_id not in enabled_adapters:
            sources.append(_unavailable_activity_source(descriptor, query, "disabled", "该模拟交易所未启用"))
        elif not descriptor["api_key"] or not descriptor["api_secret"]:
            sources.append(_unavailable_activity_source(descriptor, query, "blocked", "缺少模拟环境 API key/secret"))
        else:
            pending.append(descriptor)

    if not pending:
        return {"sources": sources, "activities": activities}

    settings, _store = build_store(config.data_dir)
    proxy_runtime = None
    try:
        proxy_runtime = ProxyRuntime.from_settings(
            settings,
            start_bridges=bool(config.start_bridges),
            start_firecrawl_bridges=False,
            start_exchange_bridges=bool(config.start_bridges),
        )
        routed = [
            (
                descriptor,
                list(
                    proxy_runtime.router.candidate_decisions(
                        str(descriptor["route_name"]),
                        str(descriptor["target_url"]),
                        attempts=max(1, min(max_routes, 5)),
                    )
                ),
            )
            for descriptor in pending
        ]
        with ThreadPoolExecutor(max_workers=min(2, len(routed)), thread_name_prefix="finbot-account-history") as executor:
            future_map = {
                executor.submit(_fetch_activity_with_routes, descriptor, routes, query): descriptor
                for descriptor, routes in routed
            }
            for future in as_completed(future_map):
                descriptor = future_map[future]
                try:
                    payload = future.result()
                except Exception as exc:
                    payload = {
                        "sources": [
                            _unavailable_activity_source(
                                descriptor,
                                query,
                                "failed",
                                _safe_account_error(exc, descriptor),
                            )
                        ],
                        "activities": [],
                    }
                sources.extend(payload.get("sources", []))
                activities.extend(payload.get("activities", []))
    except Exception as exc:
        for descriptor in pending:
            sources.append(
                _unavailable_activity_source(
                    descriptor,
                    query,
                    "failed",
                    f"代理运行时初始化失败：{_safe_account_error(exc, descriptor)}",
                )
            )
    finally:
        if proxy_runtime is not None:
            proxy_runtime.close()
    return {"sources": sources, "activities": activities}


def execute_paper_decisions(
    *,
    config: Any,
    loop_run_id: str,
    decisions: list[dict[str, Any]],
    portfolio_risk: dict[str, Any] | None,
    ai_governance: dict[str, Any] | None,
    adapter_ids: tuple[str, ...] | None = None,
) -> dict[str, Any]:
    settings, store = build_store(config.data_dir)
    enabled_adapters = tuple(dict.fromkeys(adapter_ids or config.paper_execution_adapters))
    unsupported = [adapter_id for adapter_id in enabled_adapters if adapter_id not in SUPPORTED_PAPER_ADAPTERS]
    if unsupported:
        raise ValueError(f"不支持的模拟交易 adapter：{', '.join(unsupported)}")
    if not enabled_adapters:
        raise ValueError("至少启用一个模拟交易 adapter")

    proxy_runtime = ProxyRuntime.from_settings(
        settings,
        start_bridges=config.start_bridges and config.paper_execution_submit_orders,
        start_firecrawl_bridges=False,
        start_exchange_bridges=config.start_bridges and config.paper_execution_submit_orders,
    )
    adapters = []
    try:
        if "gate_testnet" in enabled_adapters:
            gate_client = None
            gate_blocker = None
            if config.paper_execution_submit_orders and config.gate_testnet_api_key and config.gate_testnet_api_secret:
                route = proxy_runtime.router.decide("exchange:gate", GATE_TESTNET_API_BASE)
                if route.ok:
                    gate_client = GateTestnetClient(
                        config.gate_testnet_api_key,
                        config.gate_testnet_api_secret,
                        proxy=route.proxy,
                    )
                else:
                    gate_blocker = route.reason or "Gate TestNet 代理路由不可用"
            adapters.append(GateTestnetAdapter(gate_client, blocker=gate_blocker))
        if "bybit_demo" in enabled_adapters:
            bybit_client = None
            bybit_blocker = None
            if config.paper_execution_submit_orders and config.bybit_demo_api_key and config.bybit_demo_api_secret:
                route = proxy_runtime.router.decide("exchange:bybit", BYBIT_DEMO_API_BASE)
                if route.ok:
                    bybit_client = BybitDemoClient(
                        config.bybit_demo_api_key,
                        config.bybit_demo_api_secret,
                        proxy=route.proxy,
                    )
                else:
                    bybit_blocker = route.reason or "Bybit Demo 代理路由不可用"
            adapters.append(BybitDemoAdapter(bybit_client, blocker=bybit_blocker))

        engine = MultiExchangePaperExecutionEngine(store, tuple(adapters))
        try:
            report = engine.execute(
                loop_run_id=loop_run_id,
                decisions=decisions,
                portfolio_risk=portfolio_risk,
                ai_governance=ai_governance,
                policy=PaperExecutionPolicy(
                    submit_orders=config.paper_execution_submit_orders,
                    require_human_review=config.paper_execution_require_human_review,
                    max_orders_per_adapter=config.paper_execution_max_orders_per_adapter,
                    max_notional_usdt=config.paper_execution_max_notional_usdt,
                    min_confidence=config.paper_execution_min_confidence,
                    max_workers=config.paper_execution_max_workers,
                ),
            )
        finally:
            engine.close()
    finally:
        proxy_runtime.close()
    report["output"] = str(write_report(settings, "paper-execution-latest.json", report))
    return report


def _exchange_descriptors(config: Any) -> tuple[dict[str, Any], ...]:
    return (
        {
            "adapter_id": "gate_testnet",
            "display_name": "Gate TestNet",
            "provider": "gate",
            "environment": "testnet",
            "route_name": "exchange:gate",
            "target_url": GATE_TESTNET_API_BASE,
            "api_key": config.gate_testnet_api_key,
            "api_secret": config.gate_testnet_api_secret,
            "client": GateTestnetClient,
            "adapter": GateTestnetAdapter,
        },
        {
            "adapter_id": "bybit_demo",
            "display_name": "Bybit Demo",
            "provider": "bybit",
            "environment": "demo",
            "route_name": "exchange:bybit",
            "target_url": BYBIT_DEMO_API_BASE,
            "api_key": config.bybit_demo_api_key,
            "api_secret": config.bybit_demo_api_secret,
            "client": BybitDemoClient,
            "adapter": BybitDemoAdapter,
        },
    )


def _fetch_activity_with_routes(
    descriptor: dict[str, Any],
    routes: list[Any],
    query: AccountActivityQuery,
) -> dict[str, Any]:
    errors: list[str] = []
    attempted = False
    for route in routes:
        if not route.ok:
            if route.reason:
                errors.append(str(route.reason))
            continue
        attempted = True
        client = None
        try:
            client = descriptor["client"](
                str(descriptor["api_key"]),
                str(descriptor["api_secret"]),
                proxy=route.proxy,
                timeout_seconds=15,
            )
            adapter = descriptor["adapter"](client)
            return adapter.fetch_account_activity(query=query)
        except Exception as exc:
            errors.append(_safe_account_error(exc, descriptor))
        finally:
            if client is not None:
                client.close()
    return {
        "sources": [
            _unavailable_activity_source(
                descriptor,
                query,
                "failed" if attempted else "blocked",
                errors[-1] if errors else "没有可用的交易所代理路由",
            )
        ],
        "activities": [],
    }


def _unavailable_activity_source(
    descriptor: dict[str, Any],
    query: AccountActivityQuery,
    status: str,
    error: str,
) -> dict[str, Any]:
    return exchange_source(
        adapter_id=str(descriptor["adapter_id"]),
        display_name=str(descriptor["display_name"]),
        status=status,
        complete=False,
        fetched_record_count=0,
        matched_record_count=0,
        coverage_start_at=query.start_at,
        coverage_end_at=query.end_at,
        message="交易所只读订单与成交历史不可用",
        error=error,
    )


def _fetch_account_with_routes(
    descriptor: dict[str, Any],
    routes: list[Any],
    pnl_window: PnlWindow,
) -> ExchangeAccountSnapshot:
    errors: list[str] = []
    for route in routes:
        if not route.ok:
            if route.reason:
                errors.append(str(route.reason))
            continue
        client = None
        try:
            client = descriptor["client"](
                str(descriptor["api_key"]),
                str(descriptor["api_secret"]),
                proxy=route.proxy,
                timeout_seconds=15,
            )
            adapter = descriptor["adapter"](client)
            return adapter.fetch_account_snapshot(pnl_window=pnl_window)
        except Exception as exc:
            errors.append(_safe_account_error(exc, descriptor))
        finally:
            if client is not None:
                client.close()
    return _unavailable_snapshot(
        descriptor,
        status="failed" if any(route.ok for route in routes) else "blocked",
        error=errors[-1] if errors else "没有可用的交易所代理路由",
        fetched_at=utc_now(),
    )


def _unavailable_snapshot(
    descriptor: dict[str, Any],
    *,
    status: str,
    error: str,
    fetched_at: str,
) -> ExchangeAccountSnapshot:
    return blocked_account_snapshot(
        adapter_id=str(descriptor["adapter_id"]),
        display_name=str(descriptor["display_name"]),
        provider=str(descriptor["provider"]),
        environment=str(descriptor["environment"]),
        status=status,
        error=error,
        fetched_at=fetched_at,
    )


def _safe_account_error(error: Exception, descriptor: dict[str, Any]) -> str:
    message = str(error).replace("\r", " ").replace("\n", " ")
    for secret in (descriptor.get("api_key"), descriptor.get("api_secret")):
        if secret:
            message = message.replace(str(secret), "***")
    lowered = message.lower()
    if descriptor.get("adapter_id") == "bybit_demo" and (
        "cloudfront" in lowered or "block access from your country" in lowered
    ):
        return "当前代理出口被 Bybit 地区策略拦截"
    if descriptor.get("adapter_id") == "gate_testnet" and "invalid_key" in lowered:
        return "Gate TestNet 未识别当前 API key"
    if "timed out" in lowered or "timeout" in lowered:
        return "交易所账户请求超时"
    return message[:300]
