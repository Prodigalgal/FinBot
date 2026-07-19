from __future__ import annotations

import pytest

from finbot_proxy import target_probe
from finbot_proxy.round_robin import NodeAssignment
from finbot_proxy.target_probe import TargetProbeConfiguration, probe_targets


def test_failure_code_distinguishes_remote_connection_reset() -> None:
    assert target_probe._failure_code(ConnectionResetError("reset")) == "CONNECTION_RESET"


def test_probe_targets_keeps_only_successful_original_assignments(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    configuration = TargetProbeConfiguration(
        url="https://api.example.com/health",
        method="GET",
        body=None,
        expected_status=200,
        expected_body_substring=None,
        timeout_seconds=5,
        concurrency=2,
    )
    assignments = (
        NodeAssignment(index=4, port=10004),
        NodeAssignment(index=16, port=10016),
        NodeAssignment(index=18, port=10018),
    )

    def fake_probe(
        assignment: NodeAssignment,
        _configuration: TargetProbeConfiguration,
    ) -> target_probe._ProbeOutcome:
        failure = None if assignment.index in {4, 18} else "HTTP_403"
        return target_probe._ProbeOutcome(assignment, failure)

    monkeypatch.setattr(target_probe, "_probe_target", fake_probe)

    summary = probe_targets(assignments, configuration)

    assert summary.healthy_assignments == (assignments[0], assignments[2])
    assert summary.failure_counts == {"HTTP_403": 1}
