package io.omnnu.finbot.application.workflow.exception;

public final class WorkflowIdempotencyConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkflowIdempotencyConflictException() {
        super("Idempotency key was reused with different workflow input");
    }
}
