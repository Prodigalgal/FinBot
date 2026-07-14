package io.omnnu.finbot.application.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ResearchTaskModeTest {
    @Test
    void standardTaskResumesAWorkflowFromTheSecondBackgroundAttempt() {
        assertEquals(ResearchTaskMode.STANDARD, ResearchTaskMode.STANDARD.forAttempt(1));
        assertEquals(ResearchTaskMode.RESUME_FAILED, ResearchTaskMode.STANDARD.forAttempt(2));
    }

    @Test
    void explicitResumeModeRemainsActiveOnTheFirstAttempt() {
        assertEquals(ResearchTaskMode.RESUME_FAILED, ResearchTaskMode.RESUME_FAILED.forAttempt(1));
    }

    @Test
    void rejectsInvalidAttemptNumbers() {
        assertThrows(IllegalArgumentException.class, () -> ResearchTaskMode.STANDARD.forAttempt(0));
    }
}
