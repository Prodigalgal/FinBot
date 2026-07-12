from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any

from finbot.storage.sqlite_store import SQLiteStore


@dataclass(frozen=True)
class ExperimentDefinition:
    experiment_id: str
    name: str
    control_variant: str
    challenger_variants: tuple[str, ...]
    data_version: str
    workflow_version: str | None = None
    model_version: str | None = None
    status: str = "active"

    def __post_init__(self) -> None:
        if not self.experiment_id or not self.name or not self.control_variant or not self.data_version:
            raise ValueError("experiment_id/name/control_variant/data_version 不能为空")
        if self.control_variant in self.challenger_variants:
            raise ValueError("control_variant 不能同时是 challenger")
        if len(self.challenger_variants) != len(set(self.challenger_variants)):
            raise ValueError("challenger_variants 不能重复")
        if self.status not in {"draft", "active", "paused", "completed"}:
            raise ValueError("非法 experiment status")


@dataclass(frozen=True)
class ExperimentRun:
    run_id: str
    experiment_id: str
    variant_id: str
    input_hash: str
    data_version: str
    random_seed: int
    status: str
    metrics: dict[str, Any]
    config: dict[str, Any]
    created_at: str

    def __post_init__(self) -> None:
        if not self.run_id or not self.input_hash or not self.data_version:
            raise ValueError("run_id/input_hash/data_version 不能为空")
        if self.status not in {"passed", "failed", "blocked", "unavailable"}:
            raise ValueError("非法 experiment run status")


class ExperimentRegistry:
    def __init__(self, store: SQLiteStore):
        self.store = store
        self.init_schema()

    def init_schema(self) -> None:
        with self.store.connect() as connection:
            connection.executescript(
                """
                create table if not exists strategy_experiments (
                  experiment_id text primary key,
                  name text not null,
                  control_variant text not null,
                  challenger_variants_json text not null,
                  data_version text not null,
                  workflow_version text,
                  model_version text,
                  status text not null,
                  updated_at text not null
                );
                create table if not exists strategy_experiment_runs (
                  run_id text primary key,
                  experiment_id text not null,
                  variant_id text not null,
                  input_hash text not null,
                  data_version text not null,
                  random_seed integer not null,
                  status text not null,
                  metrics_json text not null,
                  config_json text not null,
                  created_at text not null,
                  unique(experiment_id, variant_id, input_hash, random_seed),
                  foreign key(experiment_id) references strategy_experiments(experiment_id)
                );
                create index if not exists idx_strategy_experiment_runs_lookup
                  on strategy_experiment_runs(experiment_id, input_hash, created_at);
                """
            )

    def save_definition(self, definition: ExperimentDefinition) -> None:
        timestamp = datetime.now(timezone.utc).isoformat()
        with self.store.connect() as connection:
            connection.execute(
                """
                insert into strategy_experiments (
                  experiment_id, name, control_variant, challenger_variants_json,
                  data_version, workflow_version, model_version, status, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(experiment_id) do update set
                  name=excluded.name,
                  control_variant=excluded.control_variant,
                  challenger_variants_json=excluded.challenger_variants_json,
                  data_version=excluded.data_version,
                  workflow_version=excluded.workflow_version,
                  model_version=excluded.model_version,
                  status=excluded.status,
                  updated_at=excluded.updated_at
                """,
                (
                    definition.experiment_id,
                    definition.name,
                    definition.control_variant,
                    json.dumps(definition.challenger_variants, ensure_ascii=False),
                    definition.data_version,
                    definition.workflow_version,
                    definition.model_version,
                    definition.status,
                    timestamp,
                ),
            )

    def get_definition(self, experiment_id: str) -> ExperimentDefinition | None:
        with self.store.connect() as connection:
            row = connection.execute(
                "select * from strategy_experiments where experiment_id = ?",
                (experiment_id,),
            ).fetchone()
        if row is None:
            return None
        return ExperimentDefinition(
            experiment_id=row["experiment_id"],
            name=row["name"],
            control_variant=row["control_variant"],
            challenger_variants=tuple(json.loads(row["challenger_variants_json"])),
            data_version=row["data_version"],
            workflow_version=row["workflow_version"],
            model_version=row["model_version"],
            status=row["status"],
        )

    def record_run(self, run: ExperimentRun) -> ExperimentRun:
        definition = self.get_definition(run.experiment_id)
        if definition is None:
            raise KeyError(f"experiment {run.experiment_id} not found")
        if run.variant_id not in {definition.control_variant, *definition.challenger_variants}:
            raise ValueError("variant_id 不属于该 experiment")
        if run.data_version != definition.data_version:
            raise ValueError("run data_version 与 experiment 不一致")
        with self.store.connect() as connection:
            connection.execute(
                """
                insert or ignore into strategy_experiment_runs (
                  run_id, experiment_id, variant_id, input_hash, data_version,
                  random_seed, status, metrics_json, config_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run.run_id,
                    run.experiment_id,
                    run.variant_id,
                    run.input_hash,
                    run.data_version,
                    run.random_seed,
                    run.status,
                    json.dumps(run.metrics, ensure_ascii=False, default=str),
                    json.dumps(run.config, ensure_ascii=False, default=str),
                    run.created_at,
                ),
            )
            row = connection.execute(
                """
                select * from strategy_experiment_runs
                where experiment_id = ? and variant_id = ? and input_hash = ? and random_seed = ?
                """,
                (run.experiment_id, run.variant_id, run.input_hash, run.random_seed),
            ).fetchone()
        return _run(row)

    def comparison(self, experiment_id: str, input_hash: str) -> dict[str, Any]:
        definition = self.get_definition(experiment_id)
        if definition is None:
            raise KeyError(f"experiment {experiment_id} not found")
        with self.store.connect() as connection:
            rows = connection.execute(
                """
                select * from strategy_experiment_runs
                where experiment_id = ? and input_hash = ?
                order by variant_id, created_at desc
                """,
                (experiment_id, input_hash),
            ).fetchall()
        latest: dict[str, ExperimentRun] = {}
        for row in rows:
            run = _run(row)
            latest.setdefault(run.variant_id, run)
        control = latest.get(definition.control_variant)
        if control is None:
            return {"status": "unavailable", "reason": "control_run_missing", "variants": []}
        comparisons = []
        for variant_id, run in sorted(latest.items()):
            comparisons.append(
                {
                    "variant_id": variant_id,
                    "role": "control" if variant_id == definition.control_variant else "challenger",
                    "run_id": run.run_id,
                    "status": run.status,
                    "metrics": run.metrics,
                    "delta_vs_control": {
                        name: _metric_delta(run.metrics.get(name), control.metrics.get(name))
                        for name in ("net_return_pct", "max_drawdown_pct", "sharpe", "sortino", "calmar")
                    },
                }
            )
        return {
            "status": "available",
            "experiment": asdict(definition),
            "input_hash": input_hash,
            "variants": comparisons,
            "reproducibility": {
                "same_data_version": len({run.data_version for run in latest.values()}) == 1,
                "seed_recorded": all(isinstance(run.random_seed, int) for run in latest.values()),
            },
        }


def _run(row: Any) -> ExperimentRun:
    return ExperimentRun(
        run_id=row["run_id"],
        experiment_id=row["experiment_id"],
        variant_id=row["variant_id"],
        input_hash=row["input_hash"],
        data_version=row["data_version"],
        random_seed=int(row["random_seed"]),
        status=row["status"],
        metrics=json.loads(row["metrics_json"] or "{}"),
        config=json.loads(row["config_json"] or "{}"),
        created_at=row["created_at"],
    )


def _metric_delta(value: Any, control: Any) -> float | None:
    if value is None or control is None:
        return None
    return round(float(value) - float(control), 8)
