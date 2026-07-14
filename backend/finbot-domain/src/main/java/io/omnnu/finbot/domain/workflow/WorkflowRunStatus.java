package io.omnnu.finbot.domain.workflow;

public enum WorkflowRunStatus {
    ACCEPTED,
    RUNNING,
    WAITING_HUMAN,
    PARTIAL,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return switch (this) {
            case PARTIAL, COMPLETED, FAILED, CANCELLED -> true;
            case ACCEPTED, RUNNING, WAITING_HUMAN -> false;
        };
    }
}
