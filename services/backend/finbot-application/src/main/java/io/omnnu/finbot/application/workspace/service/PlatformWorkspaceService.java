package io.omnnu.finbot.application.workspace.service;

import io.omnnu.finbot.application.workspace.dto.IngestionWorkspace;
import io.omnnu.finbot.application.workspace.dto.NetworkWorkspace;
import io.omnnu.finbot.application.workspace.dto.OperationsReport;
import io.omnnu.finbot.application.workspace.dto.PlatformReadiness;
import io.omnnu.finbot.application.workspace.dto.QuantWorkspace;
import io.omnnu.finbot.application.workspace.dto.WorkflowLearning;
import io.omnnu.finbot.application.workspace.port.in.PlatformWorkspaceUseCase;
import io.omnnu.finbot.application.workspace.port.out.PlatformWorkspaceRepository;

import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class PlatformWorkspaceService implements PlatformWorkspaceUseCase {
    private static final Duration MAXIMUM_REPORT_RANGE = Duration.ofDays(3660);

    private final PlatformWorkspaceRepository repository;
    private final Clock clock;

    public PlatformWorkspaceService(PlatformWorkspaceRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PlatformReadiness readiness() {
        return repository.readiness(clock.instant());
    }

    @Override
    public IngestionWorkspace ingestion(int limit) {
        requireLimit(limit);
        return repository.ingestion(limit, clock.instant());
    }

    @Override
    public QuantWorkspace quant(int limit) {
        requireLimit(limit);
        return repository.quant(limit, clock.instant());
    }

    @Override
    public OperationsReport report(Instant fromInclusive, Instant toExclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        if (Duration.between(fromInclusive, toExclusive).compareTo(MAXIMUM_REPORT_RANGE) > 0) {
            throw new IllegalArgumentException("report range must not exceed ten years");
        }
        return repository.report(fromInclusive, toExclusive, clock.instant());
    }

    @Override
    public NetworkWorkspace network() {
        return repository.network(clock.instant());
    }

    @Override
    public WorkflowLearning workflowLearning(WorkflowVersionId versionId) {
        return repository.workflowLearning(versionId, clock.instant());
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
