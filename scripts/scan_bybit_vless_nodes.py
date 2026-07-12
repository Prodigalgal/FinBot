from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import tempfile
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import httpx


PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from finbot.config.runtime_config import RuntimeConfigStore
from finbot.config.settings import Settings
from finbot.exchange.bybit_demo import BybitDemoApiError, BybitDemoClient
from finbot.network.sing_box_bridge import SingBoxBridgeConfig, SingBoxBridgeManager
from finbot.network.vless_subscription import VlessNode, load_vless_subscription


TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
BYBIT_MAINNET_TIME_URL = "https://api.bybit.com/v5/market/time"
BYBIT_MAINNET_TICKER_URL = "https://api.bybit.com/v5/market/tickers?category=linear&symbol=BTCUSDT"
BYBIT_DEMO_TIME_URL = "https://api-demo.bybit.com/v5/market/time"


@dataclass(frozen=True)
class ProbeSpec:
    name: str
    url: str
    validator: Callable[[httpx.Response], tuple[bool, str | None]]


def main() -> int:
    parser = argparse.ArgumentParser(description="只读扫描 VLESS 节点对 Bybit Mainnet 与 Demo API 的可用性")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--node-limit", type=int, default=0, help="仅用于诊断；0 表示扫描全部节点")
    parser.add_argument("--timeout-seconds", type=float, default=8.0)
    parser.add_argument("--retest-limit", type=int, default=60)
    parser.add_argument("--stable-limit", type=int, default=20)
    parser.add_argument("--auth-limit", type=int, default=8)
    parser.add_argument("--max-stable-latency-ms", type=float, default=5000.0)
    parser.add_argument(
        "--output",
        type=Path,
        default=PROJECT_ROOT / "data" / "reports" / "bybit-vless-node-scan.json",
    )
    parser.add_argument(
        "--stable-output",
        type=Path,
        default=PROJECT_ROOT / "data" / "reports" / "bybit-vless-stable-nodes.txt",
    )
    args = parser.parse_args()

    if args.workers < 1 or args.workers > 32:
        parser.error("--workers 必须在 1 到 32 之间")
    if args.retest_limit < 1 or args.stable_limit < 1 or args.auth_limit < 0:
        parser.error("复测与输出数量必须为正数，鉴权数量不能为负数")

    settings = Settings.from_env(project_root=PROJECT_ROOT)
    if not settings.exchange_vless_subscription_url and not settings.exchange_vless_subscription_file:
        raise RuntimeError("未配置 exchange VLESS 订阅")
    subscription = load_vless_subscription(
        url=settings.exchange_vless_subscription_url,
        file=settings.exchange_vless_subscription_file,
        timeout_seconds=30,
    )
    supported_nodes = tuple(
        node
        for node in subscription.nodes
        if node.transport == "ws" and node.security in {"tls", "none"}
    )
    if not supported_nodes:
        raise RuntimeError("订阅中没有当前 sing-box bridge 支持的 VLESS WS 节点")
    nodes = supported_nodes[: args.node_limit] if args.node_limit > 0 else supported_nodes

    settings.ensure_dirs()
    generated_at = _now()
    print(
        json.dumps(
            {
                "event": "scan_started",
                "subscription_nodes": len(subscription.nodes),
                "supported_nodes": len(supported_nodes),
                "scanned_nodes": len(nodes),
                "workers": args.workers,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )

    with tempfile.TemporaryDirectory(prefix="bybit-vless-scan-", dir=settings.proxy_runtime_dir) as raw_scan_dir:
        scan_dir = Path(raw_scan_dir)
        stage1 = _run_parallel(
            nodes,
            workers=args.workers,
            worker=lambda index, node: _stage1_probe(
                index,
                node,
                settings,
                scan_dir,
                args.timeout_seconds,
            ),
            stage="initial",
        )

        initial_passed = sorted(
            (result for result in stage1 if result["status"] == "passed"),
            key=_latency_sort_key,
        )
        retest_inputs = initial_passed[: args.retest_limit]
        print(
            json.dumps(
                {
                    "event": "initial_completed",
                    "passed": len(initial_passed),
                    "failed": len(stage1) - len(initial_passed),
                    "retest_selected": len(retest_inputs),
                },
                ensure_ascii=False,
            ),
            flush=True,
        )

        retest_nodes = tuple(nodes[int(result["index"])] for result in retest_inputs)
        retest_by_index = {
            int(result["index"]): result
            for result in _run_parallel(
                retest_nodes,
                workers=min(args.workers, 12),
                worker=lambda retest_index, node: _retest_probe(
                    int(retest_inputs[retest_index]["index"]),
                    node,
                    settings,
                    scan_dir,
                    args.timeout_seconds,
                    str(retest_inputs[retest_index].get("exit_ip") or ""),
                    args.max_stable_latency_ms,
                ),
                stage="retest",
            )
        }

        stable = sorted(
            (result for result in retest_by_index.values() if result["status"] == "passed"),
            key=_latency_sort_key,
        )[: args.stable_limit]
        _verify_demo_auth(
            stable,
            nodes,
            settings,
            scan_dir,
            args.timeout_seconds,
            args.auth_limit,
        )

    stage1_by_index = {int(result["index"]): result for result in stage1}
    for index, retest_result in retest_by_index.items():
        stage1_by_index[index]["retest"] = retest_result

    stable = [result for result in stable if result.get("auth_status") in {"passed", "not_tested"}]
    report = {
        "status": "completed",
        "generated_at": generated_at,
        "completed_at": _now(),
        "write_requests_sent": 0,
        "criteria": {
            "initial": "Cloudflare trace、Bybit Mainnet time、Bybit Demo time 全部成功",
            "retest": "Mainnet time x3、Mainnet ticker x1、Demo time x2 全部成功",
            "egress": "初筛与复测的真实出口 IP 和国家代码一致",
            "latency": f"复测单次最大延迟 <= {args.max_stable_latency_ms:.0f} ms",
            "auth": "排名靠前节点额外通过 Bybit Demo 私有只读 positions 请求",
        },
        "summary": {
            "subscription_nodes": len(subscription.nodes),
            "supported_nodes": len(supported_nodes),
            "scanned_nodes": len(nodes),
            "initial_passed": len(initial_passed),
            "retested": len(retest_by_index),
            "stable_selected": len(stable),
            "authenticated_passed": sum(result.get("auth_status") == "passed" for result in stable),
            "initial_failure_reasons": dict(
                Counter(str(result.get("reason") or "unknown") for result in stage1 if result["status"] != "passed")
            ),
        },
        "stable_nodes": stable,
        "nodes": [stage1_by_index[index] for index in sorted(stage1_by_index)],
    }
    output_path = args.output if args.output.is_absolute() else PROJECT_ROOT / args.output
    stable_output_path = args.stable_output if args.stable_output.is_absolute() else PROJECT_ROOT / args.stable_output
    output_path.parent.mkdir(parents=True, exist_ok=True)
    stable_output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    stable_output_path.write_text(
        "\n".join(_formatted_endpoint(result) for result in stable) + ("\n" if stable else ""),
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "event": "scan_completed",
                "summary": report["summary"],
                "stable_output": str(stable_output_path),
                "report": str(output_path),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    for result in stable:
        print(_formatted_endpoint(result), flush=True)
    return 0


def _run_parallel(
    nodes: tuple[VlessNode, ...],
    *,
    workers: int,
    worker: Callable[[int, VlessNode], dict[str, Any]],
    stage: str,
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    completed = 0
    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix=f"bybit-{stage}") as executor:
        future_map = {
            executor.submit(worker, index, node): index
            for index, node in enumerate(nodes)
        }
        for future in as_completed(future_map):
            index = future_map[future]
            try:
                result = future.result()
            except Exception as exc:
                result = {
                    "index": index,
                    "status": "failed",
                    "reason": type(exc).__name__,
                    "message": _safe_message(str(exc)),
                }
            results.append(result)
            completed += 1
            if completed % 25 == 0 or completed == len(nodes):
                passed = sum(item["status"] == "passed" for item in results)
                print(
                    json.dumps(
                        {
                            "event": "scan_progress",
                            "stage": stage,
                            "completed": completed,
                            "total": len(nodes),
                            "passed": passed,
                        },
                        ensure_ascii=False,
                    ),
                    flush=True,
                )
    return results


def _stage1_probe(
    index: int,
    node: VlessNode,
    settings: Settings,
    scan_dir: Path,
    timeout_seconds: float,
) -> dict[str, Any]:
    specs = (
        ProbeSpec("bybit_mainnet_time", BYBIT_MAINNET_TIME_URL, _validate_bybit),
        ProbeSpec("bybit_demo_time", BYBIT_DEMO_TIME_URL, _validate_bybit),
    )
    return _with_proxy(
        index,
        node,
        settings,
        scan_dir / f"initial-{index:04d}",
        timeout_seconds,
        lambda client: _run_probe_set(client, specs, include_trace=True),
    )


def _retest_probe(
    index: int,
    node: VlessNode,
    settings: Settings,
    scan_dir: Path,
    timeout_seconds: float,
    expected_exit_ip: str,
    max_stable_latency_ms: float,
) -> dict[str, Any]:
    specs = (
        ProbeSpec("bybit_mainnet_time_1", BYBIT_MAINNET_TIME_URL, _validate_bybit),
        ProbeSpec("bybit_mainnet_time_2", BYBIT_MAINNET_TIME_URL, _validate_bybit),
        ProbeSpec("bybit_mainnet_time_3", BYBIT_MAINNET_TIME_URL, _validate_bybit),
        ProbeSpec("bybit_mainnet_ticker", BYBIT_MAINNET_TICKER_URL, _validate_bybit),
        ProbeSpec("bybit_demo_time_1", BYBIT_DEMO_TIME_URL, _validate_bybit),
        ProbeSpec("bybit_demo_time_2", BYBIT_DEMO_TIME_URL, _validate_bybit),
    )
    result = _with_proxy(
        index,
        node,
        settings,
        scan_dir / f"retest-{index:04d}",
        timeout_seconds,
        lambda client: _run_probe_set(client, specs, include_trace=True),
    )
    if result["status"] != "passed":
        return result
    if not expected_exit_ip or result.get("exit_ip") != expected_exit_ip:
        result.update({"status": "failed", "reason": "egress_changed"})
        return result
    if float(result.get("max_latency_ms") or math.inf) > max_stable_latency_ms:
        result.update({"status": "failed", "reason": "latency_above_threshold"})
    return result


def _with_proxy(
    index: int,
    node: VlessNode,
    settings: Settings,
    work_dir: Path,
    timeout_seconds: float,
    probe: Callable[[httpx.Client], dict[str, Any]],
) -> dict[str, Any]:
    base = {
        "index": index,
        "endpoint": _endpoint(node),
        "name": node.name,
    }
    manager = SingBoxBridgeManager(
        (node,),
        SingBoxBridgeConfig(
            binary_path=settings.sing_box_path,
            work_dir=work_dir,
            max_nodes=1,
            startup_timeout_seconds=min(6.0, timeout_seconds),
        ),
    )
    try:
        proxies = manager.start()
        if not proxies:
            return {**base, "status": "failed", "reason": "unsupported_node"}
        timeout = httpx.Timeout(timeout_seconds, connect=min(5.0, timeout_seconds))
        with httpx.Client(
            proxy=proxies[0],
            timeout=timeout,
            follow_redirects=False,
            trust_env=False,
            headers={"User-Agent": "FinBot Bybit route diagnostics"},
        ) as client:
            result = probe(client)
        return {**base, **result}
    except Exception as exc:
        return {
            **base,
            "status": "failed",
            "reason": type(exc).__name__,
            "message": _safe_message(str(exc)),
        }
    finally:
        manager.close()


def _run_probe_set(
    client: httpx.Client,
    specs: tuple[ProbeSpec, ...],
    *,
    include_trace: bool,
) -> dict[str, Any]:
    attempts: list[dict[str, Any]] = []
    exit_ip = None
    country_code = None
    if include_trace:
        trace_attempt, trace_values = _probe_trace(client)
        attempts.append(trace_attempt)
        exit_ip = trace_values.get("ip")
        country_code = trace_values.get("loc")
    for spec in specs:
        attempts.append(_probe_http(client, spec))
    passed = all(attempt["status"] == "passed" for attempt in attempts)
    latencies = [float(attempt["latency_ms"]) for attempt in attempts if attempt.get("latency_ms") is not None]
    result: dict[str, Any] = {
        "status": "passed" if passed else "failed",
        "exit_ip": exit_ip,
        "country_code": country_code,
        "attempts": attempts,
        "success_rate": round(sum(attempt["status"] == "passed" for attempt in attempts) / len(attempts), 4),
        "median_latency_ms": round(statistics.median(latencies), 1) if latencies else None,
        "max_latency_ms": round(max(latencies), 1) if latencies else None,
    }
    if not passed:
        failed = next(attempt for attempt in attempts if attempt["status"] != "passed")
        result["reason"] = failed.get("reason") or f"{failed['name']}_failed"
    return result


def _probe_trace(client: httpx.Client) -> tuple[dict[str, Any], dict[str, str]]:
    started = time.perf_counter()
    try:
        response = client.get(TRACE_URL)
        latency_ms = (time.perf_counter() - started) * 1000
        values = _parse_trace(response.text) if response.status_code == 200 else {}
        passed = bool(values.get("ip") and values.get("loc"))
        attempt = {
            "name": "cloudflare_trace",
            "status": "passed" if passed else "failed",
            "http_status": response.status_code,
            "latency_ms": round(latency_ms, 1),
        }
        if not passed:
            attempt["reason"] = "trace_invalid"
        return attempt, values
    except Exception as exc:
        return {
            "name": "cloudflare_trace",
            "status": "failed",
            "reason": type(exc).__name__,
            "message": _safe_message(str(exc)),
            "latency_ms": round((time.perf_counter() - started) * 1000, 1),
        }, {}


def _probe_http(client: httpx.Client, spec: ProbeSpec) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        response = client.get(spec.url, headers={"Accept": "application/json"})
        latency_ms = (time.perf_counter() - started) * 1000
        passed, reason = spec.validator(response)
        result = {
            "name": spec.name,
            "status": "passed" if passed else "failed",
            "http_status": response.status_code,
            "latency_ms": round(latency_ms, 1),
        }
        if reason:
            result["reason"] = reason
        return result
    except Exception as exc:
        return {
            "name": spec.name,
            "status": "failed",
            "reason": type(exc).__name__,
            "message": _safe_message(str(exc)),
            "latency_ms": round((time.perf_counter() - started) * 1000, 1),
        }


def _verify_demo_auth(
    stable: list[dict[str, Any]],
    nodes: tuple[VlessNode, ...],
    settings: Settings,
    scan_dir: Path,
    timeout_seconds: float,
    auth_limit: int,
) -> None:
    values = RuntimeConfigStore(PROJECT_ROOT).values()
    api_key = str(values.get("paper_execution.bybit_demo_api_key") or "").strip()
    api_secret = str(values.get("paper_execution.bybit_demo_api_secret") or "").strip()
    credentials_configured = bool(api_key and api_secret)
    for rank, result in enumerate(stable):
        if rank >= auth_limit or not credentials_configured:
            result["auth_status"] = "not_tested"
            continue
        node = nodes[int(result["index"])]
        manager = SingBoxBridgeManager(
            (node,),
            SingBoxBridgeConfig(
                binary_path=settings.sing_box_path,
                work_dir=scan_dir / f"auth-{int(result['index']):04d}",
                max_nodes=1,
                startup_timeout_seconds=min(6.0, timeout_seconds),
            ),
        )
        client = None
        try:
            proxy = manager.start()[0]
            client = BybitDemoClient(api_key, api_secret, proxy=proxy, timeout_seconds=timeout_seconds)
            positions = client.list_positions("BTCUSDT")
            result["auth_status"] = "passed"
            result["auth_result_count"] = len(positions)
        except BybitDemoApiError as exc:
            result["auth_status"] = "failed"
            result["auth_reason"] = f"BybitDemoApiError:{exc.status_code}:{exc.code}"
        except Exception as exc:
            result["auth_status"] = "failed"
            result["auth_reason"] = type(exc).__name__
        finally:
            if client is not None:
                client.close()
            manager.close()


def _validate_bybit(response: httpx.Response) -> tuple[bool, str | None]:
    if response.status_code != 200:
        if response.status_code == 403:
            return False, "bybit_geo_blocked"
        return False, f"http_{response.status_code}"
    try:
        payload = response.json()
    except ValueError:
        return False, "invalid_json"
    if not isinstance(payload, dict):
        return False, "invalid_payload"
    code = payload.get("retCode")
    if code != 0:
        return False, f"bybit_ret_{code}"
    return True, None


def _parse_trace(value: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in value.splitlines():
        if "=" not in line:
            continue
        key, raw_value = line.split("=", 1)
        result[key.strip()] = raw_value.strip()
    return result


def _endpoint(node: VlessNode) -> str:
    address = f"[{node.address}]" if ":" in node.address else node.address
    return f"{address}:{node.port}"


def _formatted_endpoint(result: dict[str, Any]) -> str:
    country_code = str(result.get("country_code") or "未知")
    return f"{result['endpoint']}#{country_code}"


def _latency_sort_key(result: dict[str, Any]) -> tuple[float, float, int]:
    return (
        float(result.get("median_latency_ms") or math.inf),
        float(result.get("max_latency_ms") or math.inf),
        int(result.get("index") or 0),
    )


def _safe_message(value: str) -> str:
    return " ".join(value.split())[:180]


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


if __name__ == "__main__":
    raise SystemExit(main())
