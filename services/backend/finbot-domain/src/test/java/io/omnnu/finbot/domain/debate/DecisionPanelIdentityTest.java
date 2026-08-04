package io.omnnu.finbot.domain.debate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DecisionPanelIdentityTest {
    @Test
    void validatesPanelKeysAndInputHashes() {
        assertEquals("research", DecisionPanelKey.RESEARCH.value());
        assertEquals("principal_review", new DecisionPanelKey(" principal_review ").value());
        assertEquals("a".repeat(64), new DecisionPanelInputHash("a".repeat(64)).value());

        assertThrows(IllegalArgumentException.class, () -> new DecisionPanelKey("RESEARCH"));
        assertThrows(IllegalArgumentException.class, () -> new DecisionPanelKey("x"));
        assertThrows(IllegalArgumentException.class, () -> new DecisionPanelInputHash("not-a-hash"));
    }
}
