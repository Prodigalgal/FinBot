package io.omnnu.finbot.domain.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ReasoningEffortTest {

    @Test
    void enforcesModelCapabilityWithoutSilentlyDowngrading() {
        assertTrue(ReasoningEffort.XHIGH.supports(ReasoningEffort.XHIGH));
        assertTrue(ReasoningEffort.XHIGH.supports(ReasoningEffort.HIGH));
        assertTrue(ReasoningEffort.XHIGH.supports(ReasoningEffort.PROVIDER_DEFAULT));
        assertFalse(ReasoningEffort.XHIGH.supports(ReasoningEffort.MAX));
        assertFalse(ReasoningEffort.PROVIDER_DEFAULT.supports(ReasoningEffort.HIGH));
    }
}
