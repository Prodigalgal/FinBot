package io.omnnu.finbot.operations.runtime;

import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.application.operations.service.TaskCancellationToken;
import io.omnnu.finbot.application.research.dto.ResearchPipelineRequest;
import io.omnnu.finbot.application.research.port.in.ResearchPipelineUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class UserSelectedResearchRunner {
    private final ResearchPipelineUseCase pipeline;

    public UserSelectedResearchRunner(ResearchPipelineUseCase pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    public CompletionStage<Void> execute(List<ResearchPipelineRequest> requests) {
        var cancellation = TaskCancellationContext.current().orElse(null);
        var failures = new ArrayList<Throwable>();
        CompletionStage<Void> sequence = CompletableFuture.completedFuture(null);
        for (var request : requests) {
            sequence = sequence.thenCompose(ignored -> executeOne(request, cancellation).handle((result, failure) -> {
                if (failure != null) {
                    var cause = unwrap(failure);
                    if (cause instanceof CancellationException) throw new CompletionException(cause);
                    failures.add(cause);
                }
                return null;
            }));
        }
        return sequence.thenCompose(ignored -> {
            if (failures.isEmpty()) return CompletableFuture.completedFuture(null);
            var failure = new IllegalStateException("部分指定商品研究失败", failures.getFirst());
            failures.stream().skip(1).forEach(failure::addSuppressed);
            return CompletableFuture.failedFuture(failure);
        });
    }

    private CompletionStage<?> executeOne(ResearchPipelineRequest request, TaskCancellationToken cancellation) {
        try {
            return cancellation == null ? pipeline.execute(request)
                    : TaskCancellationContext.call(cancellation, () -> pipeline.execute(request));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
