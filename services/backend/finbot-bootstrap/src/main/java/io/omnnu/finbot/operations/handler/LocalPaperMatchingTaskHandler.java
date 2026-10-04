package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.dto.LocalPaperMatchingTaskPayload;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.application.paper.port.in.LocalPaperUseCase;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class LocalPaperMatchingTaskHandler implements BackgroundTaskHandler {
    private final LocalPaperUseCase paper;
    public LocalPaperMatchingTaskHandler(LocalPaperUseCase paper) { this.paper = Objects.requireNonNull(paper, "paper"); }
    @Override public BackgroundTaskType taskType() { return BackgroundTaskType.LOCAL_PAPER_MATCHING; }
    @Override public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof LocalPaperMatchingTaskPayload payload)) throw new IllegalArgumentException("Invalid paper matching payload");
        return paper.matchDue(payload.limit()).thenApply(ignored -> null);
    }
}
