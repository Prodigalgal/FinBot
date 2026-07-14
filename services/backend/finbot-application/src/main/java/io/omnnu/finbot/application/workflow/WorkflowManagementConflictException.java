package io.omnnu.finbot.application.workflow;

public final class WorkflowManagementConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkflowManagementConflictException(String message) {
        super(message);
    }
}
