package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.ai.service.AiExecutionFailure;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.SdbScaExecutionException;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.domain.debate.DebateArtifact;
import io.omnnu.finbot.domain.debate.DebateArtifactStatus;
import io.omnnu.finbot.domain.debate.DebatePhase;
import io.omnnu.finbot.domain.debate.DebatePhaseStatus;
import io.omnnu.finbot.domain.debate.DebatePhaseType;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.debate.DebateTask;
import io.omnnu.finbot.domain.debate.DebateTaskStatus;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

final class SdbScaPhaseExecutor {
    private static final int CLAIM_BATCH_SIZE = 64;

    private final DebateProtocolStore protocolStore;
    private final AiExecutionPolicyExecutor aiExecution;
    private final Clock clock;
    private final Executor executor;

    SdbScaPhaseExecutor(
            DebateProtocolStore protocolStore,
            AiExecutionPolicyExecutor aiExecution,
            Clock clock,
            Executor executor) {
        this.protocolStore = Objects.requireNonNull(protocolStore, "protocolStore");
        this.aiExecution = Objects.requireNonNull(aiExecution, "aiExecution");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    PhaseResult execute(
            WorkflowExecutionContext execution,
            DebateSession session,
            int generation,
            DebatePhaseType phaseType,
            List<TaskCommand> commands) {
        var immutableCommands = List.copyOf(commands);
        if (immutableCommands.isEmpty()) {
            throw new SdbScaExecutionException(
                    "SDB_PHASE_HAS_NO_TASKS",
                    "SDB-SCA phase has no executable tasks: " + phaseType,
                    false);
        }
        var phaseId = WorkflowExecutionIds.phase(session.debateId(), generation, phaseType);
        var existingPhase = protocolStore.phase(phaseId);
        var now = clock.instant();
        var deadline = existingPhase.map(DebatePhase::deadline)
                .orElseGet(() -> now.plus(execution.definitionVersion()
                        .debateProtocolConfiguration()
                        .stageTimeout()));
        var commandByTaskId = new LinkedHashMap<String, TaskCommand>();
        var tasks = immutableCommands.stream()
                .map(command -> {
                    var taskId = WorkflowExecutionIds.debateTask(
                            phaseId,
                            command.node().nodeId(),
                            command.targetCandidateId(),
                            command.variant());
                    if (commandByTaskId.put(taskId.value(), command) != null) {
                        throw new IllegalArgumentException("Duplicate SDB-SCA task seed");
                    }
                    return new DebateTask(
                            taskId,
                            phaseId,
                            command.node().nodeId(),
                            command.node().logicalRoleKey(),
                            command.targetCandidateId(),
                            command.variant(),
                            taskInputHash(command),
                            DebateTaskStatus.PENDING,
                            0,
                            null,
                            null,
                            now,
                            null);
                })
                .toList();
        protocolStore.createPhase(
                new DebatePhase(
                        phaseId,
                        session.debateId(),
                        DebateProtocol.SDB_SCA_V1,
                        generation,
                        phaseType,
                        DebatePhaseStatus.OPEN,
                        tasks.size(),
                        0,
                        deadline,
                        now,
                        null,
                        null,
                        0),
                tasks);

        var partial = false;
        while (true) {
            TaskCancellationContext.throwIfCancelled();
            var claimed = protocolStore.claimTasks(
                    phaseId,
                    "sdb:" + execution.runId().value(),
                    CLAIM_BATCH_SIZE,
                    leaseDuration(deadline),
                    clock.instant());
            if (claimed.isEmpty()) {
                break;
            }
            var futures = claimed.stream()
                    .map(task -> CompletableFuture.supplyAsync(
                            () -> executeTask(execution, task, commandByTaskId, deadline),
                            executor))
                    .toList();
            await(futures);
            partial = partial || futures.stream().map(CompletableFuture::join).anyMatch(TaskResult::failed);
        }

        var persistedTasks = protocolStore.tasks(phaseId);
        if (persistedTasks.stream().anyMatch(task -> !terminal(task.status()))) {
            if (!clock.instant().isBefore(deadline)) {
                persistedTasks.stream()
                        .filter(task -> !terminal(task.status()))
                        .forEach(task -> protocolStore.timeoutTask(task.taskId(), clock.instant()));
                partial = true;
            } else {
                throw new SdbScaExecutionException(
                        "SDB_PHASE_LEASE_BUSY",
                        "SDB-SCA phase contains tasks leased by another execution attempt",
                        true);
            }
        }
        var phase = protocolStore.phase(phaseId).orElseThrow(() -> new SdbScaExecutionException(
                "SDB_PHASE_MISSING",
                "SDB-SCA phase disappeared during execution",
                false));
        if (!phase.barrierSatisfied()) {
            throw new SdbScaExecutionException(
                    "SDB_PHASE_BARRIER_INCOMPLETE",
                    "SDB-SCA phase barrier did not reach its required task count",
                    true);
        }
        var revealed = protocolStore.revealPhase(phaseId, phase.version(), clock.instant());
        if (!revealed) {
            var latest = protocolStore.phase(phaseId).orElseThrow();
            if (latest.status() != DebatePhaseStatus.REVEALED
                    && latest.status() != DebatePhaseStatus.COMPLETED) {
                throw new SdbScaExecutionException(
                        "SDB_PHASE_REVEAL_CONFLICT",
                        "SDB-SCA phase could not cross its reveal barrier",
                        true);
            }
        }
        return new PhaseResult(
                protocolStore.tasks(phaseId),
                protocolStore.revealedArtifacts(phaseId),
                partial || protocolStore.tasks(phaseId).stream()
                        .anyMatch(task -> task.status() != DebateTaskStatus.COMPLETED));
    }

    private TaskResult executeTask(
            WorkflowExecutionContext execution,
            DebateTask task,
            Map<String, TaskCommand> commandByTaskId,
            Instant deadline) {
        var command = commandByTaskId.get(task.taskId().value());
        if (command == null) {
            throw new SdbScaExecutionException(
                    "SDB_TASK_SEED_MISMATCH",
                    "Claimed SDB-SCA task does not match the immutable phase seed",
                    false);
        }
        try {
            var parsed = aiExecution.execute(
                    execution.runId(),
                    execution.definitionVersion(),
                    command.node(),
                    command.prompt(),
                    deadline,
                    1,
                    command.outputNormalizer(),
                    AiExecutionPolicyExecutor.AiAttemptListener.noOp());
            var canonicalJson = parsed.parsed();
            var artifact = new DebateArtifact(
                    WorkflowExecutionIds.debateArtifact(task.taskId()),
                    task.taskId(),
                    task.phaseId(),
                    DebateArtifactStatus.SEALED,
                    WorkflowExecutionIds.sha256(canonicalJson),
                    canonicalJson,
                    clock.instant(),
                    null);
            TaskCancellationContext.throwIfCancelled();
            protocolStore.sealArtifact(artifact, task.leaseOwner(), clock.instant());
            return new TaskResult(false);
        } catch (AiExecutionFailure failure) {
            TaskCancellationContext.throwIfCancelled();
            protocolStore.failTask(
                    task.taskId(),
                    task.leaseOwner(),
                    failure.errorCode(),
                    failure.getMessage(),
                    clock.instant());
            return new TaskResult(true);
        }
    }

    private Duration leaseDuration(Instant deadline) {
        var remaining = Duration.between(clock.instant(), deadline);
        if (remaining.compareTo(Duration.ofSeconds(30)) < 0) {
            return Duration.ofSeconds(30);
        }
        return remaining.compareTo(Duration.ofHours(2)) > 0 ? Duration.ofHours(2) : remaining;
    }

    private static String taskInputHash(TaskCommand command) {
        var node = command.node();
        var primary = node.primaryAiBinding();
        var fallback = node.fallbackAiBinding();
        return WorkflowExecutionIds.sha256(
                command.prompt(),
                Objects.requireNonNullElse(node.systemPrompt(), ""),
                primary.providerProfileId().value(),
                primary.modelName(),
                primary.reasoningEffort().name(),
                fallback == null ? "" : fallback.providerProfileId().value(),
                fallback == null ? "" : fallback.modelName(),
                fallback == null ? "" : fallback.reasoningEffort().name(),
                Integer.toString(node.maximumOutputTokens()),
                Integer.toString(node.timeoutSeconds()));
    }

    private static void await(List<CompletableFuture<TaskResult>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private static boolean terminal(DebateTaskStatus status) {
        return status == DebateTaskStatus.COMPLETED
                || status == DebateTaskStatus.FAILED
                || status == DebateTaskStatus.TIMED_OUT
                || status == DebateTaskStatus.CANCELLED;
    }

    record TaskCommand(
            WorkflowNodeDefinition node,
            String targetCandidateId,
            DebateTaskVariant variant,
            String prompt,
            Function<String, String> outputNormalizer) {
        TaskCommand {
            Objects.requireNonNull(node, "node");
            targetCandidateId = targetCandidateId == null || targetCandidateId.isBlank()
                    ? null
                    : targetCandidateId.strip();
            Objects.requireNonNull(variant, "variant");
            prompt = Objects.requireNonNull(prompt, "prompt").strip();
            Objects.requireNonNull(outputNormalizer, "outputNormalizer");
            if (prompt.isEmpty()) {
                throw new IllegalArgumentException("prompt must not be blank");
            }
        }
    }

    record PhaseResult(
            List<DebateTask> tasks,
            List<DebateArtifact> artifacts,
            boolean partial) {
        PhaseResult {
            tasks = List.copyOf(tasks).stream()
                    .sorted(Comparator.comparing(task -> task.taskId().value()))
                    .toList();
            artifacts = List.copyOf(artifacts).stream()
                    .sorted(Comparator.comparing(artifact -> artifact.artifactId().value()))
                    .toList();
        }
    }

    private record TaskResult(boolean failed) {
    }
}
