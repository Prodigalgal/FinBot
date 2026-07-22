package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.dto.ForecastEvaluationTaskPayload;
import io.omnnu.finbot.application.research.port.in.ForecastEvaluationUseCase;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class ForecastEvaluationTaskHandler implements BackgroundTaskHandler {
    private final ForecastEvaluationUseCase evaluation;

    public ForecastEvaluationTaskHandler(ForecastEvaluationUseCase evaluation) {
        this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.FORECAST_EVALUATION;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof ForecastEvaluationTaskPayload payload)) {
            throw new IllegalArgumentException("Forecast evaluation task has an invalid payload");
        }
        return evaluation.evaluateDue(payload.limit()).thenApply(ignored -> null);
    }
}
