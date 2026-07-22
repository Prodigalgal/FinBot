package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import java.time.Duration;
import java.util.Objects;

public record AiRuntimeBinding(
        AiProtocol protocol,
        ReasoningEffort maximumReasoningEffort,
        Duration capacityWaitTimeout) {

    public AiRuntimeBinding(AiProtocol protocol, ReasoningEffort maximumReasoningEffort) {
        this(protocol, maximumReasoningEffort, Duration.ofMinutes(30));
    }

    public AiRuntimeBinding {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(maximumReasoningEffort, "maximumReasoningEffort");
        Objects.requireNonNull(capacityWaitTimeout, "capacityWaitTimeout");
        if (capacityWaitTimeout.compareTo(Duration.ofSeconds(5)) < 0
                || capacityWaitTimeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("capacityWaitTimeout must be between five seconds and two hours");
        }
    }
}
