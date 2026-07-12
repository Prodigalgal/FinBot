from __future__ import annotations

import math
import random
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from statistics import mean, pstdev
from typing import Any


@dataclass(frozen=True)
class ReturnObservation:
    timestamp: datetime
    net_return: float
    benchmark_return: float = 0.0
    signal_timestamp: datetime | None = None
    execution_timestamp: datetime | None = None

    def __post_init__(self) -> None:
        if self.timestamp.tzinfo is None:
            raise ValueError("timestamp 必须包含时区")
        if not -1 < self.net_return < 10 or not -1 < self.benchmark_return < 10:
            raise ValueError("return 必须在 -1 到 10 之间")
        for value in (self.signal_timestamp, self.execution_timestamp):
            if value is not None and value.tzinfo is None:
                raise ValueError("signal/execution timestamp 必须包含时区")


@dataclass(frozen=True)
class ValidationConfig:
    initial_train_size: int = 30
    test_size: int = 10
    periods_per_year: int = 365
    monte_carlo_paths: int = 500
    random_seed: int = 20260712
    minimum_monte_carlo_samples: int = 20

    def __post_init__(self) -> None:
        if self.initial_train_size < 10 or self.test_size < 5:
            raise ValueError("walk-forward train/test 样本过少")
        if self.periods_per_year < 1:
            raise ValueError("periods_per_year 必须大于 0")
        if not 100 <= self.monte_carlo_paths <= 10_000:
            raise ValueError("monte_carlo_paths 必须在 100 到 10000 之间")


class StrategyValidationEngine:
    def validate(
        self,
        *,
        variants: dict[str, list[ReturnObservation]],
        control_variant: str,
        config: ValidationConfig | None = None,
    ) -> dict[str, Any]:
        policy = config or ValidationConfig()
        if not variants or control_variant not in variants:
            raise ValueError("control_variant 不存在")
        lookahead = _lookahead_check(variants)
        if lookahead["status"] == "failed":
            return {
                "status": "blocked",
                "reason": "lookahead_bias_detected",
                "lookahead_check": lookahead,
                "walk_forward": _unavailable("前视偏差检查未通过"),
                "monte_carlo": _unavailable("前视偏差检查未通过"),
                "sensitivity": [],
                "ablation": [],
                "config": asdict(policy),
            }
        control = variants[control_variant]
        sensitivity = [
            {
                "variant_id": variant_id,
                "sample_count": len(rows),
                "metrics": performance_metrics(rows, policy.periods_per_year),
            }
            for variant_id, rows in sorted(variants.items())
        ]
        minimum = policy.initial_train_size + policy.test_size
        walk_forward = (
            _walk_forward(variants, policy)
            if len(control) >= minimum
            else _unavailable(f"至少需要 {minimum} 个对齐样本")
        )
        monte_carlo = (
            _monte_carlo(control, policy)
            if len(control) >= policy.minimum_monte_carlo_samples
            else _unavailable(f"至少需要 {policy.minimum_monte_carlo_samples} 个样本")
        )
        return {
            "status": "available" if walk_forward["status"] == "available" else "partial",
            "control_variant": control_variant,
            "sample_count": len(control),
            "control_metrics": performance_metrics(control, policy.periods_per_year),
            "lookahead_check": lookahead,
            "walk_forward": walk_forward,
            "monte_carlo": monte_carlo,
            "sensitivity": sensitivity,
            "ablation": _ablation(sensitivity, control_variant),
            "config": asdict(policy),
            "methodology": {
                "returns": "periodic_net_returns_after_fees",
                "selection": "best_in_sample_sharpe_then_out_of_sample_evaluation",
                "monte_carlo": "seeded_iid_bootstrap_of_control_net_returns",
                "benchmark_alpha": "geometric_strategy_return_minus_geometric_benchmark_return",
                "future_data_fill": False,
            },
        }


def performance_metrics(rows: list[ReturnObservation], periods_per_year: int) -> dict[str, Any]:
    if not rows:
        return {"status": "unavailable", "reason": "empty_sample", "sample_count": 0}
    returns = [row.net_return for row in rows]
    benchmark = [row.benchmark_return for row in rows]
    equity = 1.0
    peak = 1.0
    max_drawdown = 0.0
    for value in returns:
        equity *= 1.0 + value
        peak = max(peak, equity)
        max_drawdown = max(max_drawdown, (peak - equity) / peak if peak else 0.0)
    benchmark_equity = math.prod(1.0 + value for value in benchmark)
    average = mean(returns)
    volatility = pstdev(returns) if len(returns) > 1 else 0.0
    downside = [min(0.0, value) for value in returns]
    downside_deviation = math.sqrt(mean(value * value for value in downside))
    annualization = math.sqrt(periods_per_year)
    sharpe = average / volatility * annualization if volatility > 0 else None
    sortino = average / downside_deviation * annualization if downside_deviation > 0 else None
    years = len(returns) / periods_per_year
    annualized_return = equity ** (1.0 / years) - 1.0 if years > 0 and equity > 0 else None
    calmar = annualized_return / max_drawdown if annualized_return is not None and max_drawdown > 0 else None
    wins = [value for value in returns if value > 0]
    losses = [value for value in returns if value < 0]
    gross_loss = abs(sum(losses))
    return {
        "status": "available",
        "sample_count": len(rows),
        "net_return_pct": _pct(equity - 1.0),
        "benchmark_return_pct": _pct(benchmark_equity - 1.0),
        "benchmark_alpha_pct": _pct(equity - benchmark_equity),
        "max_drawdown_pct": _pct(max_drawdown),
        "annualized_return_pct": _pct(annualized_return),
        "annualized_volatility_pct": _pct(volatility * annualization),
        "sharpe": _round(sharpe),
        "sortino": _round(sortino),
        "calmar": _round(calmar),
        "win_rate_pct": _pct(len(wins) / len(returns)),
        "average_win_pct": _pct(mean(wins) if wins else None),
        "average_loss_pct": _pct(mean(losses) if losses else None),
        "profit_factor": _round(sum(wins) / gross_loss if gross_loss > 0 else None),
    }


def _lookahead_check(variants: dict[str, list[ReturnObservation]]) -> dict[str, Any]:
    issues: list[dict[str, Any]] = []
    reference_timestamps: list[datetime] | None = None
    for variant_id, rows in variants.items():
        timestamps = [row.timestamp.astimezone(timezone.utc) for row in rows]
        if len(timestamps) != len(set(timestamps)):
            issues.append({"variant_id": variant_id, "code": "duplicate_timestamp"})
        if timestamps != sorted(timestamps):
            issues.append({"variant_id": variant_id, "code": "non_monotonic_timestamp"})
        for index, row in enumerate(rows):
            if row.signal_timestamp and row.execution_timestamp and row.signal_timestamp > row.execution_timestamp:
                issues.append({"variant_id": variant_id, "index": index, "code": "signal_after_execution"})
            if row.execution_timestamp and row.execution_timestamp > row.timestamp:
                issues.append({"variant_id": variant_id, "index": index, "code": "execution_after_return_period"})
        if reference_timestamps is None:
            reference_timestamps = timestamps
        elif timestamps != reference_timestamps:
            issues.append({"variant_id": variant_id, "code": "variant_timestamps_not_aligned"})
    return {
        "status": "passed" if not issues else "failed",
        "issue_count": len(issues),
        "issues": issues,
        "checks": [
            "timezone_aware",
            "strict_timestamp_order",
            "aligned_variant_samples",
            "signal_not_after_execution",
            "execution_not_after_return_period",
        ],
    }


def _walk_forward(variants: dict[str, list[ReturnObservation]], config: ValidationConfig) -> dict[str, Any]:
    sample_count = len(next(iter(variants.values())))
    folds: list[dict[str, Any]] = []
    train_end = config.initial_train_size
    while train_end + config.test_size <= sample_count:
        train_start = max(0, train_end - config.initial_train_size)
        candidates = []
        for variant_id, rows in sorted(variants.items()):
            metrics = performance_metrics(rows[train_start:train_end], config.periods_per_year)
            score = metrics.get("sharpe")
            candidates.append((float(score) if score is not None else float("-inf"), variant_id, metrics))
        _, selected, train_metrics = max(candidates, key=lambda item: (item[0], item[1]))
        folds.append(
            {
                "fold": len(folds) + 1,
                "train": {"start": train_start, "end_exclusive": train_end, "metrics": train_metrics},
                "test": {
                    "start": train_end,
                    "end_exclusive": train_end + config.test_size,
                    "metrics": performance_metrics(variants[selected][train_end:train_end + config.test_size], config.periods_per_year),
                },
                "selected_variant": selected,
            }
        )
        train_end += config.test_size
    out_of_sample = [
        row
        for fold in folds
        for row in variants[fold["selected_variant"]][fold["test"]["start"]:fold["test"]["end_exclusive"]]
    ]
    return {
        "status": "available" if folds else "unavailable",
        "fold_count": len(folds),
        "folds": folds,
        "out_of_sample_metrics": performance_metrics(out_of_sample, config.periods_per_year),
    }


def _monte_carlo(rows: list[ReturnObservation], config: ValidationConfig) -> dict[str, Any]:
    randomizer = random.Random(config.random_seed)
    returns = [row.net_return for row in rows]
    finals: list[float] = []
    drawdowns: list[float] = []
    for _ in range(config.monte_carlo_paths):
        equity = 1.0
        peak = 1.0
        drawdown = 0.0
        for value in (randomizer.choice(returns) for _ in returns):
            equity *= 1.0 + value
            peak = max(peak, equity)
            drawdown = max(drawdown, (peak - equity) / peak if peak else 0.0)
        finals.append(equity - 1.0)
        drawdowns.append(drawdown)
    return {
        "status": "available",
        "paths": config.monte_carlo_paths,
        "seed": config.random_seed,
        "net_return_pct": _percentiles(finals),
        "max_drawdown_pct": _percentiles(drawdowns),
        "probability_of_loss_pct": _pct(sum(value < 0 for value in finals) / len(finals)),
    }


def _ablation(sensitivity: list[dict[str, Any]], control_variant: str) -> list[dict[str, Any]]:
    metrics_by_id = {row["variant_id"]: row["metrics"] for row in sensitivity}
    control = metrics_by_id[control_variant]
    return [
        {
            "agent_id": variant_id.removeprefix("without:"),
            "variant_id": variant_id,
            "net_return_contribution_pct": _difference(control.get("net_return_pct"), metrics.get("net_return_pct")),
            "sharpe_contribution": _difference(control.get("sharpe"), metrics.get("sharpe")),
            "interpretation": "positive_means_control_outperformed_ablation",
        }
        for variant_id, metrics in metrics_by_id.items()
        if variant_id.startswith("without:")
    ]


def _unavailable(reason: str) -> dict[str, Any]:
    return {"status": "unavailable", "reason": reason}


def _percentiles(values: list[float]) -> dict[str, float | None]:
    ordered = sorted(values)
    return {
        "p05": _pct(ordered[int((len(ordered) - 1) * 0.05)]),
        "median": _pct(ordered[int((len(ordered) - 1) * 0.50)]),
        "p95": _pct(ordered[int((len(ordered) - 1) * 0.95)]),
    }


def _difference(left: Any, right: Any) -> float | None:
    return _round(float(left) - float(right)) if left is not None and right is not None else None


def _pct(value: float | None) -> float | None:
    return _round(value * 100.0) if value is not None else None


def _round(value: float | None) -> float | None:
    return round(float(value), 8) if value is not None and math.isfinite(float(value)) else None
