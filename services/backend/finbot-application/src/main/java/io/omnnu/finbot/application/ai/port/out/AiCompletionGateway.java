package io.omnnu.finbot.application.ai.port.out;

import io.omnnu.finbot.application.ai.dto.AiCompletionEvent;
import io.omnnu.finbot.application.ai.dto.AiCompletionRequest;

import java.util.concurrent.Flow;

public interface AiCompletionGateway {
    Flow.Publisher<AiCompletionEvent> stream(AiCompletionRequest request);
}
