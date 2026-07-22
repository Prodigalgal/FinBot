package io.omnnu.finbot.application.workflow.exception;

public final class WorkflowManagementConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkflowManagementConflictException(String message) {
        super(message);
    }
}
