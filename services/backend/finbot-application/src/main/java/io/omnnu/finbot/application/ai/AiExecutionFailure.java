package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;
import java.util.Objects;

public final class AiExecutionFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean retryable;
    private final String invocationIdValue;

    AiExecutionFailure(
            String errorCode,
            String safeMessage,
            boolean retryable,
            AiInvocationId invocationId) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.retryable = retryable;
        this.invocationIdValue = invocationId == null ? null : invocationId.value();
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public AiInvocationId invocationId() {
        return invocationIdValue == null ? null : new AiInvocationId(invocationIdValue);
    }
}
