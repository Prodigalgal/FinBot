package io.omnnu.finbot.application.ai;

import java.util.concurrent.Flow;

public interface AiCompletionGateway {
    Flow.Publisher<AiCompletionEvent> stream(AiCompletionRequest request);
}
