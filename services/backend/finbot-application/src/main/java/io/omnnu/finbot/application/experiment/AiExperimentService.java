package io.omnnu.finbot.application.experiment;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.WorkflowManagementConflictException;
import io.omnnu.finbot.application.workflow.WorkflowManagementUseCase;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class AiExperimentService implements AiExperimentUseCase {
    private final AiExperimentRepository repository;
    private final WorkflowManagementUseCase workflows;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public AiExperimentService(
            AiExperimentRepository repository,
            WorkflowManagementUseCase workflows,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<AiExperiment> list() {
        return repository.list();
    }

    @Override
    public AiExperiment save(SaveAiExperimentCommand command) {
        Objects.requireNonNull(command, "command");
        var control = workflows.version(new WorkflowVersionId(command.controlWorkflowVersionId()));
        var candidate = workflows.version(new WorkflowVersionId(command.candidateWorkflowVersionId()));
        if (!control.definitionId().equals(candidate.definitionId())) {
            throw new IllegalArgumentException("Experiment variants must belong to the same workflow definition");
        }
        if (command.status() == AiExperimentStatus.RUNNING) {
            if (control.status() != WorkflowVersionStatus.PUBLISHED
                    || candidate.status() == WorkflowVersionStatus.DRAFT) {
                throw new IllegalArgumentException(
                        "Running experiments require the current published control and an immutable candidate");
            }
            var controlIsCurrent = workflows.definitions().stream()
                    .filter(value -> value.definitionId().equals(control.definitionId()))
                    .anyMatch(value -> control.versionId().equals(value.publishedVersionId()));
            if (!controlIsCurrent) {
                throw new IllegalArgumentException(
                        "Experiment control must be the currently published workflow version");
            }
            if (repository.hasRunningOverlap(
                    command.experimentId(),
                    command.controlWorkflowVersionId(),
                    command.candidateWorkflowVersionId())) {
                throw new WorkflowManagementConflictException(
                        "同一工作流版本已参与其他运行中实验");
            }
        }
        var now = clock.instant();
        if (command.experimentId() == null || command.experimentId().isBlank()) {
            if (command.expectedVersion() != null) {
                throw new IllegalArgumentException("expectedVersion must be omitted when creating an experiment");
            }
            var experiment = experiment(command, idGenerator.next("experiment_"), 0, 0, 0, now, now);
            return repository.create(experiment);
        }
        var current = repository.find(command.experimentId())
                .orElseThrow(() -> new IllegalArgumentException("AI experiment does not exist"));
        if (command.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required when updating an experiment");
        }
        validateTransition(current.status(), command.status());
        var updated = experiment(
                command,
                current.experimentId(),
                current.controlSampleCount(),
                current.candidateSampleCount(),
                command.expectedVersion() + 1,
                current.createdAt(),
                now);
        return repository.update(updated, command.expectedVersion(), now)
                .orElseThrow(() -> new WorkflowManagementConflictException(
                        "AI 实验已被修改，请刷新后重试"));
    }

    private static AiExperiment experiment(
            SaveAiExperimentCommand command,
            String experimentId,
            long controlSampleCount,
            long candidateSampleCount,
            long version,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
        return new AiExperiment(
                experimentId,
                command.displayName(),
                command.status(),
                command.controlWorkflowVersionId(),
                command.candidateWorkflowVersionId(),
                command.candidateAllocationBasisPoints(),
                command.evaluationMetric(),
                command.minimumSampleSize(),
                controlSampleCount,
                candidateSampleCount,
                version,
                createdAt,
                updatedAt);
    }

    private static void validateTransition(AiExperimentStatus current, AiExperimentStatus target) {
        if (current == target) {
            return;
        }
        var allowed = switch (current) {
            case DRAFT -> target == AiExperimentStatus.RUNNING;
            case RUNNING -> target == AiExperimentStatus.PAUSED || target == AiExperimentStatus.COMPLETED;
            case PAUSED -> target == AiExperimentStatus.RUNNING || target == AiExperimentStatus.COMPLETED;
            case COMPLETED -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("Invalid AI experiment status transition");
        }
    }
}
