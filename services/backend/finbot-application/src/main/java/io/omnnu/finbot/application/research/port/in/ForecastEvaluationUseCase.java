package io.omnnu.finbot.application.research.port.in;

import java.util.concurrent.CompletionStage;

public interface ForecastEvaluationUseCase {
    CompletionStage<Integer> evaluateDue(int limit);
}
