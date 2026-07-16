from __future__ import annotations

import concurrent.futures
import math
import socket
import ssl
import urllib.error
import urllib.request
from collections import Counter
from dataclasses import dataclass
from urllib.parse import urlsplit

from finbot_proxy.round_robin import NodeAssignment


@dataclass(frozen=True, slots=True)
class TargetProbeConfiguration:
    url: str
    method: str
    body: str | None
    expected_status: int
    expected_body_substring: str | None
    timeout_seconds: float
    concurrency: int

    def __post_init__(self) -> None:
        parsed = urlsplit(self.url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username is not None:
            raise ValueError("PROXY_PROBE_URL must be an HTTPS URL without user-info")
        if self.method not in {"GET", "HEAD", "POST"}:
            raise ValueError("PROXY_PROBE_METHOD must be GET, HEAD, or POST")
        if self.body is not None and len(self.body.encode("utf-8")) > 4096:
            raise ValueError("PROXY_PROBE_BODY must not exceed 4096 bytes")
        if self.method in {"GET", "HEAD"} and self.body is not None:
            raise ValueError("PROXY_PROBE_BODY is only supported for POST")
        if self.expected_status < 100 or self.expected_status > 599:
            raise ValueError("PROXY_PROBE_EXPECTED_STATUS must be a valid HTTP status")
        if self.expected_body_substring is not None and len(self.expected_body_substring) > 200:
            raise ValueError("PROXY_PROBE_EXPECTED_BODY must not exceed 200 characters")
        if (
            not math.isfinite(self.timeout_seconds)
            or self.timeout_seconds <= 0
            or self.timeout_seconds > 60
        ):
            raise ValueError("PROXY_PROBE_TIMEOUT_SECONDS must be between 0 and 60")
        if self.concurrency < 1 or self.concurrency > 16:
            raise ValueError("PROXY_PROBE_CONCURRENCY must be between 1 and 16")

    @property
    def target(self) -> str:
        return urlsplit(self.url).hostname or "unknown"


@dataclass(frozen=True, slots=True)
class TargetProbeSummary:
    healthy_assignments: tuple[NodeAssignment, ...]
    failure_counts: dict[str, int]


def probe_targets(
    assignments: tuple[NodeAssignment, ...],
    configuration: TargetProbeConfiguration,
) -> TargetProbeSummary:
    if not assignments:
        return TargetProbeSummary((), {})
    worker_count = min(configuration.concurrency, len(assignments))
    with concurrent.futures.ThreadPoolExecutor(
        max_workers=worker_count,
        thread_name_prefix="proxy-target-probe",
    ) as executor:
        outcomes = tuple(
            executor.map(
                lambda assignment: _probe_target(assignment, configuration),
                assignments,
            )
        )
    healthy = tuple(outcome.assignment for outcome in outcomes if outcome.failure_code is None)
    failures = Counter(
        outcome.failure_code for outcome in outcomes if outcome.failure_code is not None
    )
    return TargetProbeSummary(healthy, dict(sorted(failures.items())))


@dataclass(frozen=True, slots=True)
class _ProbeOutcome:
    assignment: NodeAssignment
    failure_code: str | None


def _probe_target(
    assignment: NodeAssignment,
    configuration: TargetProbeConfiguration,
) -> _ProbeOutcome:
    proxy_url = f"http://127.0.0.1:{assignment.port}"
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy_url, "https": proxy_url})
    )
    body = configuration.body.encode("utf-8") if configuration.body is not None else None
    headers = {"User-Agent": "FinBot proxy target probe/2.0"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        configuration.url,
        data=body,
        headers=headers,
        method=configuration.method,
    )
    try:
        with opener.open(request, timeout=configuration.timeout_seconds) as response:
            response_body = response.read(4096).decode("utf-8", "replace")
            if response.status != configuration.expected_status:
                return _ProbeOutcome(assignment, f"HTTP_{response.status}")
            expected_body = configuration.expected_body_substring
            if expected_body is not None and expected_body not in response_body:
                return _ProbeOutcome(assignment, "BODY_MISMATCH")
            return _ProbeOutcome(assignment, None)
    except urllib.error.HTTPError as error:
        try:
            error.read(4096)
            return _ProbeOutcome(assignment, f"HTTP_{error.code}")
        finally:
            error.close()
    except Exception as error:
        return _ProbeOutcome(assignment, _failure_code(error))


def _failure_code(error: Exception) -> str:
    reason = error.reason if isinstance(error, urllib.error.URLError) else error
    if isinstance(reason, (TimeoutError, socket.timeout)):
        return "TIMEOUT"
    if isinstance(reason, ssl.SSLError):
        return "TLS_ERROR"
    if isinstance(reason, (ConnectionError, OSError)):
        return "CONNECTION_ERROR"
    return type(reason).__name__.upper()[:64]
