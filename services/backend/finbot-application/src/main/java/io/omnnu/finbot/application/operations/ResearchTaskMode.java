package io.omnnu.finbot.application.operations;

public enum ResearchTaskMode {
    STANDARD,
    RESUME_FAILED;

    public ResearchTaskMode forAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        return this == RESUME_FAILED || attemptNumber > 1 ? RESUME_FAILED : STANDARD;
    }
}
