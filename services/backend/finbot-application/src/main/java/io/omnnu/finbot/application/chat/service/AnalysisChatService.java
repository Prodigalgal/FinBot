package io.omnnu.finbot.application.chat.service;

import io.omnnu.finbot.application.chat.dto.AnalysisChatSession;
import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import io.omnnu.finbot.application.chat.exception.AnalysisChatNotFoundException;
import io.omnnu.finbot.application.chat.port.in.AnalysisChatUseCase;
import io.omnnu.finbot.application.chat.port.out.AnalysisChatStore;
import io.omnnu.finbot.application.research.port.in.ResearchLaunchUseCase;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.port.in.WorkflowManagementUseCase;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AnalysisChatService implements AnalysisChatUseCase {
    private final AnalysisChatStore store;
    private final ResearchLaunchUseCase researchLaunch;
    private final WorkflowManagementUseCase workflows;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public AnalysisChatService(
            AnalysisChatStore store,
            ResearchLaunchUseCase researchLaunch,
            WorkflowManagementUseCase workflows,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.researchLaunch = Objects.requireNonNull(researchLaunch, "researchLaunch");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AnalysisChatSession create(String workflowVersionId) {
        var versionId = new WorkflowVersionId(required(workflowVersionId, "workflowVersionId", 80));
        if (workflows.version(versionId).status() != WorkflowVersionStatus.PUBLISHED) {
            throw new IllegalArgumentException("Chat requires a published workflow version");
        }
        var now = clock.instant();
        return store.create(new AnalysisChatSession(
                idGenerator.next("chat_"), "新对话", versionId.value(), now, now));
    }

    @Override
    public List<AnalysisChatSession> list(String beforeChatId, String search, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        var cursor = beforeChatId == null ? null : required(beforeChatId, "beforeChatId", 80);
        var query = search == null ? "" : search.strip();
        if (query.length() > 160) {
            throw new IllegalArgumentException("search must contain at most 160 characters");
        }
        return store.list(cursor, query, limit);
    }

    @Override
    public AnalysisChatSession find(String chatId) {
        return store.find(chatId).orElseThrow(() -> new AnalysisChatNotFoundException(chatId));
    }

    @Override
    public List<AnalysisChatTurn> turns(String chatId, Integer beforeTurnNumber, int limit) {
        find(chatId);
        if (limit < 1 || limit > 100 || beforeTurnNumber != null && beforeTurnNumber < 1) {
            throw new IllegalArgumentException("Invalid chat turn pagination");
        }
        return store.turns(chatId, beforeTurnNumber, limit);
    }

    @Override
    public CompletionStage<AnalysisChatTurn> send(
            String chatId,
            String message,
            String clientRequestKey) {
        var session = find(chatId);
        var normalizedMessage = required(message, "message", 2_000);
        var requestKey = IdempotencyKeys.scoped("analysis-chat-turn", required(clientRequestKey,
                "Idempotency-Key", 200));
        var previousTurns = store.turns(chatId, null, 6);
        var prompt = AnalysisChatContextComposer.compose(previousTurns, normalizedMessage);
        var reserved = store.reserve(
                chatId,
                idGenerator.next("chatturn_"),
                requestKey,
                normalizedMessage,
                prompt,
                clock.instant());
        if (reserved.workflowRunId() != null) {
            return CompletableFuture.completedFuture(reserved);
        }
        var workflowKey = IdempotencyKeys.scoped("analysis-chat-run", chatId + ':' + reserved.requestKey());
        var command = new StartWorkflowCommand(
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.API,
                new WorkflowVersionId(session.workflowVersionId()),
                reserved.workflowPrompt(),
                workflowKey);
        return researchLaunch.launchAnalysis(command, workflowKey)
                .thenApply(launched -> store.attach(
                        reserved.turnId(),
                        launched.workflow().runId().value(),
                        launched.task().taskId().value(),
                        clock.instant()));
    }

    private static String required(String value, String name, int maximumLength) {
        var normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain 1 to " + maximumLength + " characters");
        }
        return normalized;
    }
}
