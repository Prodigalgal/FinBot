package io.omnnu.finbot.application.workflow.exception;

public final class WorkflowNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkflowNotFoundException(String message) {
        super(message);
    }
}
