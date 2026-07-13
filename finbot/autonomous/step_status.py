from __future__ import annotations

from typing import Any


def is_failed_step_output(step_name: str, output: dict[str, Any]) -> bool:
    status = str(output.get("status") or "").strip().lower()
    if status != "blocked":
        return status in {"failed", "error"}
    if step_name == "execution_robot":
        return False
    if step_name == "paper_execution":
        summary = output.get("summary") if isinstance(output.get("summary"), dict) else {}
        executions = output.get("executions") if isinstance(output.get("executions"), list) else []
        execution_count = int(summary.get("execution_count") or len(executions))
        reasons = summary.get("reasons") if isinstance(summary.get("reasons"), list) else []
        return execution_count > 0 or not reasons
    return True
