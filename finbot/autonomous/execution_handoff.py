from __future__ import annotations

from typing import Any


def resolve_paper_execution_handoff(
    robot_report: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    status = str(robot_report.get("status") or "").strip().lower()
    if status == "passed":
        decisions = robot_report.get("approved_decisions")
        return [item for item in decisions or [] if isinstance(item, dict)], None
    summary = robot_report.get("summary") if isinstance(robot_report.get("summary"), dict) else {}
    reasons = [str(reason) for reason in summary.get("reasons", []) if str(reason).strip()]
    if status in {"empty", "skipped"}:
        return [], _terminal_report(
            "passed",
            reasons or ["执行机器人没有批准方向性候选，本轮无需模拟下单"],
        )
    if status == "blocked":
        return [], _terminal_report(
            "blocked",
            reasons or ["执行机器人触发确定性风险门禁，本轮禁止模拟下单"],
        )
    return [], _terminal_report("blocked", ["执行机器人未通过，按 fail-closed 禁止模拟下单"])


def _terminal_report(status: str, reasons: list[str]) -> dict[str, Any]:
    return {
        "status": status,
        "execution_run_id": None,
        "summary": {"execution_count": 0, "reasons": reasons},
        "executions": [],
    }
