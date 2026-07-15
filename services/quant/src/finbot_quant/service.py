from __future__ import annotations

import asyncio
import logging
import secrets
from collections.abc import AsyncIterator
from contextlib import suppress
from datetime import UTC, datetime
from typing import Annotated, assert_never

from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse

from finbot_quant.api_models import (
    ArtifactReferenceModel,
    CapabilityDescriptor,
    HealthResponse,
    QuantCapabilitiesResponse,
    StartResearchRequest,
)
from finbot_quant.cancellation import DuplicateResearchRunError, ResearchCancellationRegistry
from finbot_quant.event_models import (
    QuantMetricModel,
    ResearchAcceptedEvent,
    ResearchArtifactEvent,
    ResearchCompletedEvent,
    ResearchEvent,
    ResearchFailedEvent,
    ResearchProgressEvent,
)
from finbot_quant.indicators import INDICATOR_DESCRIPTIONS
from finbot_quant.models import (
    AcceptedUpdate,
    ArtifactReference,
    ArtifactUpdate,
    CompletedUpdate,
    FailedUpdate,
    ProgressUpdate,
    ResearchEngine,
    ResearchErrorCode,
    ResearchJob,
    ResearchUpdate,
)
from finbot_quant.strategies import STRATEGY_DESCRIPTIONS

LOGGER = logging.getLogger(__name__)
HEARTBEAT_SECONDS = 15


def create_app(
    engine: ResearchEngine,
    *,
    service_token: str,
    cancellation_registry: ResearchCancellationRegistry | None = None,
) -> FastAPI:
    if len(service_token) < 16:
        raise ValueError("service_token must contain at least 16 characters")
    registry = cancellation_registry or ResearchCancellationRegistry()
    app = FastAPI(
        title="FinBot Quant Research Internal API",
        version="1.0.0",
        docs_url=None,
        redoc_url=None,
    )

    async def require_service_token(
        authorization: Annotated[str | None, Header()] = None,
    ) -> None:
        expected = f"Bearer {service_token}"
        if authorization is None or not secrets.compare_digest(authorization, expected):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid internal service token")

    @app.exception_handler(RequestValidationError)
    async def validation_problem(
        request: Request,
        exception: RequestValidationError,
    ) -> JSONResponse:
        del request
        return _problem(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "Request validation failed",
            _safe_validation_detail(exception),
        )

    @app.exception_handler(HTTPException)
    async def http_problem(request: Request, exception: HTTPException) -> JSONResponse:
        del request
        return _problem(
            exception.status_code,
            "Internal API request failed",
            str(exception.detail),
        )

    @app.get("/internal/v1/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse()

    @app.get(
        "/internal/v1/capabilities",
        response_model=QuantCapabilitiesResponse,
        dependencies=[Depends(require_service_token)],
    )
    async def capabilities() -> QuantCapabilitiesResponse:
        return QuantCapabilitiesResponse(
            strategies=tuple(
                CapabilityDescriptor(capability_id=name, description=description)
                for name, description in STRATEGY_DESCRIPTIONS.items()
            ),
            indicators=tuple(
                CapabilityDescriptor(capability_id=name, description=description)
                for name, description in INDICATOR_DESCRIPTIONS.items()
            ),
        )

    @app.post(
        "/internal/v1/research-runs:stream",
        response_class=StreamingResponse,
        dependencies=[Depends(require_service_token)],
        responses={
            200: {"content": {"text/event-stream": {}}},
            401: {"content": {"application/problem+json": {}}},
            409: {"content": {"application/problem+json": {}}},
            422: {"content": {"application/problem+json": {}}},
        },
    )
    async def stream_research(
        payload: StartResearchRequest,
        request: Request,
        idempotency_key: Annotated[
            str,
            Header(alias="Idempotency-Key", min_length=8, max_length=120),
        ],
    ) -> StreamingResponse:
        job = payload.to_domain(idempotency_key)
        try:
            cancellation = await registry.register(job.research_run_id)
        except DuplicateResearchRunError as exception:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                "researchRunId is already active on this worker",
            ) from exception

        return StreamingResponse(
            _stream_events(engine, registry, job, cancellation, request),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache, no-transform",
                "X-Accel-Buffering": "no",
            },
        )

    return app


async def _stream_events(
    engine: ResearchEngine,
    registry: ResearchCancellationRegistry,
    job: ResearchJob,
    cancellation: asyncio.Event,
    request: Request,
) -> AsyncIterator[str]:
    sequence = 0
    terminal = False
    update_task: asyncio.Task[ResearchUpdate] | None = None
    iterator = engine.stream(job, cancellation).__aiter__()
    try:
        while not terminal:
            if await request.is_disconnected():
                cancellation.set()
                return
            if update_task is None:
                update_task = asyncio.create_task(_next_update(iterator))
            done, _ = await asyncio.wait({update_task}, timeout=HEARTBEAT_SECONDS)
            if not done:
                yield ": heartbeat\n\n"
                continue
            try:
                update = update_task.result()
            except StopAsyncIteration:
                update_task = None
                break
            update_task = None
            if sequence == 0 and not isinstance(update, AcceptedUpdate):
                raise RuntimeError("research engine must emit AcceptedUpdate first")
            sequence += 1
            event = _event(job.research_run_id, sequence, update)
            terminal = isinstance(update, CompletedUpdate | FailedUpdate)
            yield _sse(event)

        if not terminal and not await request.is_disconnected():
            sequence += 1
            yield _sse(
                _event(
                    job.research_run_id,
                    sequence,
                    FailedUpdate(
                        (
                            ResearchErrorCode.CANCELLED
                            if cancellation.is_set()
                            else ResearchErrorCode.INTERNAL
                        ),
                        (
                            "Quantitative research was cancelled"
                            if cancellation.is_set()
                            else "Quantitative research ended without a terminal result"
                        ),
                        False,
                    ),
                )
            )
    except asyncio.CancelledError:
        cancellation.set()
        raise
    except Exception:
        LOGGER.exception("Quant research engine failed for run %s", job.research_run_id)
        if not await request.is_disconnected():
            sequence += 1
            yield _sse(
                _event(
                    job.research_run_id,
                    sequence,
                    FailedUpdate(
                        ResearchErrorCode.INTERNAL,
                        "Quantitative research failed inside the isolated engine",
                        False,
                    ),
                )
            )
    finally:
        cancellation.set()
        if update_task is not None and not update_task.done():
            update_task.cancel()
            with suppress(asyncio.CancelledError):
                await update_task
        await registry.release(job.research_run_id, cancellation)


async def _next_update(iterator: AsyncIterator[ResearchUpdate]) -> ResearchUpdate:
    return await anext(iterator)


def _event(
    research_run_id: str,
    sequence: int,
    update: ResearchUpdate,
) -> ResearchEvent:
    event_id = f"quant_event_{secrets.token_hex(16)}"
    occurred_at = datetime.now(UTC)
    match update:
        case AcceptedUpdate(engine_version, input_fingerprint):
            return ResearchAcceptedEvent(
                event_id=event_id,
                research_run_id=research_run_id,
                sequence=sequence,
                occurred_at=occurred_at,
                engine_version=engine_version,
                input_fingerprint=input_fingerprint,
            )
        case ProgressUpdate(stage, progress_basis_points, safe_summary):
            return ResearchProgressEvent(
                event_id=event_id,
                research_run_id=research_run_id,
                sequence=sequence,
                occurred_at=occurred_at,
                stage=stage,
                progress_basis_points=progress_basis_points,
                safe_summary=safe_summary,
            )
        case ArtifactUpdate(artifact):
            return ResearchArtifactEvent(
                event_id=event_id,
                research_run_id=research_run_id,
                sequence=sequence,
                occurred_at=occurred_at,
                artifact=_artifact(artifact),
            )
        case CompletedUpdate(metrics, artifacts, observation_count, result_fingerprint):
            return ResearchCompletedEvent(
                event_id=event_id,
                research_run_id=research_run_id,
                sequence=sequence,
                occurred_at=occurred_at,
                metrics=tuple(
                    QuantMetricModel(name=value.name, value=value.value, unit=value.unit)
                    for value in metrics
                ),
                artifacts=tuple(_artifact(value) for value in artifacts),
                observation_count=observation_count,
                result_fingerprint=result_fingerprint,
            )
        case FailedUpdate(code, safe_message, retryable):
            return ResearchFailedEvent(
                event_id=event_id,
                research_run_id=research_run_id,
                sequence=sequence,
                occurred_at=occurred_at,
                code=code,
                safe_message=safe_message,
                retryable=retryable,
            )
    assert_never(update)


def _artifact(value: ArtifactReference) -> ArtifactReferenceModel:
    return ArtifactReferenceModel(
        kind=value.kind,
        uri=value.uri,
        sha256_hex=value.sha256_hex,
        media_type=value.media_type,
        byte_size=value.byte_size,
    )


def _sse(event: ResearchEvent) -> str:
    return (
        f"id: {event.sequence}\n"
        f"event: {event.event_type}\n"
        f"data: {event.model_dump_json(by_alias=True)}\n\n"
    )


def _problem(status_code: int, title: str, detail: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        media_type="application/problem+json",
        content={
            "type": "about:blank",
            "title": title,
            "status": status_code,
            "detail": detail,
        },
    )


def _safe_validation_detail(exception: RequestValidationError) -> str:
    first_error = exception.errors()[0]
    location = ".".join(str(value) for value in first_error["loc"])
    return f"{location}: {first_error['msg']}"
