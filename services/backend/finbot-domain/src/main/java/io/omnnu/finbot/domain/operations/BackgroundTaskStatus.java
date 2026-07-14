package io.omnnu.finbot.domain.operations;

public enum BackgroundTaskStatus {
    PENDING,
    CLAIMED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, FAILED, CANCELLED -> true;
            case PENDING, CLAIMED -> false;
        };
    }
}
