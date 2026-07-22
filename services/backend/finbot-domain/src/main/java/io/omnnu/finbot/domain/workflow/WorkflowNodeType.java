package io.omnnu.finbot.domain.workflow;

public enum WorkflowNodeType {
    INPUT,
    ROUTER,
    DETERMINISTIC,
    COLLECTOR,
    CLEANER,
    AI_CLEANER,
    COMPRESSOR,
    COMPRESSION_VALIDATOR,
    AGENT,
    GATE,
    QUANT,
    RISK,
    SUBFLOW,
    HUMAN_REVIEW,
    AGGREGATOR,
    CHAIR,
    SOCIAL_CHOICE,
    EXECUTION_REVIEW,
    OUTPUT;

    public boolean llmBacked() {
        return switch (this) {
            case AI_CLEANER, COMPRESSOR, COMPRESSION_VALIDATOR,
                    AGENT, AGGREGATOR, CHAIR, EXECUTION_REVIEW -> true;
            case INPUT, ROUTER, DETERMINISTIC, COLLECTOR, CLEANER, GATE, QUANT,
                    RISK, SUBFLOW, HUMAN_REVIEW, SOCIAL_CHOICE, OUTPUT -> false;
        };
    }
}
