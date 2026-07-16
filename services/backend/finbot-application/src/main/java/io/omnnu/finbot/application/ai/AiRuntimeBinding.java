package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import java.util.Objects;

public record AiRuntimeBinding(
        AiProtocol protocol,
        ReasoningEffort maximumReasoningEffort) {

    public AiRuntimeBinding {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(maximumReasoningEffort, "maximumReasoningEffort");
    }
}
