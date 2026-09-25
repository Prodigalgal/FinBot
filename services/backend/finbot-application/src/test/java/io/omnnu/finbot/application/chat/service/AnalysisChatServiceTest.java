package io.omnnu.finbot.application.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.chat.dto.AnalysisChatSession;
import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import io.omnnu.finbot.application.chat.port.out.AnalysisChatStore;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.research.dto.ResearchLaunchResult;
import io.omnnu.finbot.application.research.port.in.ResearchLaunchUseCase;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.port.in.WorkflowManagementUseCase;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AnalysisChatServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");

    @Test
    void aCompletedTaskStillWaitsForItsWorkflowToFinish() {
        var waiting = new AnalysisChatTurn(
                "chatturn_waiting", "chat_test001", 1, "key_waiting", "分析问题", "分析问题",
                "run_waiting", "task_waiting", WorkflowRunStatus.WAITING_HUMAN,
                BackgroundTaskStatus.COMPLETED, null, null, NOW);

        assertFalse(waiting.finished());
        assertTrue(new AnalysisChatTurn(
                waiting.turnId(), waiting.chatId(), waiting.turnNumber(), waiting.requestKey(),
                waiting.userMessage(), waiting.workflowPrompt(), waiting.workflowRunId(), waiting.taskId(),
                WorkflowRunStatus.COMPLETED, BackgroundTaskStatus.COMPLETED,
                null, "共识", NOW).finished());
    }

    @Test
    void sendsChatThroughAnalysisOnlyLaunchPath() {
        var session = new AnalysisChatSession(
                "chat_test001", "研究问题", "workflowversion_test001", NOW, NOW);
        var store = new ReservingStore(session);
        var launchedCommand = new AtomicReference<StartWorkflowCommand>();
        var marker = new IllegalStateException("launch reached");
        ResearchLaunchUseCase launcher = new ResearchLaunchUseCase() {
            @Override
            public CompletionStage<ResearchLaunchResult> launch(
                    StartWorkflowCommand command, String key, ResearchTaskMode mode) {
                throw new AssertionError("Full research launch must not be used for a chat");
            }

            @Override
            public CompletionStage<ResearchLaunchResult> launchAnalysis(
                    StartWorkflowCommand command, String key) {
                launchedCommand.set(command);
                return CompletableFuture.failedStage(marker);
            }
        };
        var workflows = (WorkflowManagementUseCase) Proxy.newProxyInstance(
                WorkflowManagementUseCase.class.getClassLoader(),
                new Class<?>[] {WorkflowManagementUseCase.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Workflow management is not used while sending");
                });
        var service = new AnalysisChatService(
                store, launcher, workflows, prefix -> prefix + "test001",
                Clock.fixed(NOW, ZoneOffset.UTC));

        var failure = assertThrows(CompletionException.class, () ->
                service.send(session.chatId(), "  研究问题  ", "request_001")
                        .toCompletableFuture().join());

        assertSame(marker, failure.getCause());
        assertEquals(WorkflowType.INSTANT_RESEARCH, launchedCommand.get().workflowType());
        assertEquals(session.workflowVersionId(), launchedCommand.get().workflowVersionId().value());
        assertEquals("研究问题", launchedCommand.get().requestSummary());
    }

    private static final class ReservingStore implements AnalysisChatStore {
        private final AnalysisChatSession session;

        private ReservingStore(AnalysisChatSession session) {
            this.session = session;
        }

        @Override
        public AnalysisChatSession create(AnalysisChatSession created) {
            throw new AssertionError("Unexpected chat creation");
        }

        @Override
        public List<AnalysisChatSession> list(String beforeChatId, String search, int limit) {
            throw new AssertionError("Unexpected chat listing");
        }

        @Override
        public Optional<AnalysisChatSession> find(String chatId) {
            return chatId.equals(session.chatId()) ? Optional.of(session) : Optional.empty();
        }

        @Override
        public List<AnalysisChatTurn> turns(String chatId, Integer beforeTurnNumber, int limit) {
            return List.of();
        }

        @Override
        public AnalysisChatTurn reserve(
                String chatId, String turnId, String requestKey, String userMessage,
                String workflowPrompt, Instant createdAt) {
            return new AnalysisChatTurn(
                    turnId, chatId, 1, requestKey, userMessage, workflowPrompt,
                    null, null, null, null, null, null, createdAt);
        }

        @Override
        public AnalysisChatTurn attach(String turnId, String workflowRunId, String taskId, Instant acceptedAt) {
            throw new AssertionError("Failed launch must not attach a run");
        }
    }
}
