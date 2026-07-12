from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from finbot.council.models import CouncilCondition


_MISSING = object()


def evaluate_condition(condition: CouncilCondition | None, context: Mapping[str, Any]) -> bool:
    if condition is None:
        return True
    actual = resolve_condition_field(context, condition.field)
    operator = condition.operator
    expected = condition.value
    if operator == "exists":
        return actual is not _MISSING
    if operator == "truthy":
        return actual is not _MISSING and bool(actual)
    if operator == "falsy":
        return actual is _MISSING or not bool(actual)
    if actual is _MISSING:
        return False
    if operator == "eq":
        return actual == expected
    if operator == "ne":
        return actual != expected
    if operator == "in":
        return _contains(expected, actual)
    if operator == "not_in":
        return not _contains(expected, actual)
    if operator == "contains":
        return _contains(actual, expected)
    if operator in {"gt", "gte", "lt", "lte"}:
        comparison = _compare(actual, expected)
        if comparison is None:
            return False
        return {
            "gt": comparison > 0,
            "gte": comparison >= 0,
            "lt": comparison < 0,
            "lte": comparison <= 0,
        }[operator]
    return False


def resolve_condition_field(context: Mapping[str, Any], field_name: str) -> Any:
    current: Any = context
    for segment in field_name.split("."):
        if isinstance(current, Mapping):
            if segment not in current:
                return _MISSING
            current = current[segment]
            continue
        if isinstance(current, Sequence) and not isinstance(current, (str, bytes, bytearray)):
            try:
                current = current[int(segment)]
            except (ValueError, IndexError):
                return _MISSING
            continue
        return _MISSING
    return current


def _contains(container: Any, member: Any) -> bool:
    if isinstance(container, Mapping):
        return member in container
    if isinstance(container, (str, bytes, bytearray)):
        return str(member) in str(container)
    if isinstance(container, Sequence) or isinstance(container, set):
        return member in container
    return False


def _compare(left: Any, right: Any) -> int | None:
    if isinstance(left, bool) or isinstance(right, bool):
        return None
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return (left > right) - (left < right)
    if isinstance(left, str) and isinstance(right, str):
        return (left > right) - (left < right)
    return None
