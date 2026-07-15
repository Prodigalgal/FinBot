package io.omnnu.finbot.application.research;

import java.util.concurrent.CompletionStage;

public interface ForecastEvaluationUseCase {
    CompletionStage<Integer> evaluateDue(int limit);
}
