package io.omnnu.finbot.domain.workflow;

public enum WorkflowCheckpointStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    SKIPPED,
    WAITING_HUMAN,
    FAILED;

    public boolean finalStatus() {
        return switch (this) {
            case COMPLETED, SKIPPED, WAITING_HUMAN, FAILED -> true;
            case PENDING, RUNNING -> false;
        };
    }
}
