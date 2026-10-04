package io.omnnu.finbot.domain.research;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HypothesisStatusTest {
    @Test void immatureHypothesesCannotSkipVerificationAndTerminalRecordsCannotBeRewritten() {
        assertFalse(HypothesisStatus.PENDING_VALIDATION.allows(HypothesisStatus.CONFIRMED));
        assertFalse(HypothesisStatus.WATCHING.allows(HypothesisStatus.PAPER_VALIDATION));
        assertTrue(HypothesisStatus.WATCHING.allows(HypothesisStatus.CONFIRMED));
        assertTrue(HypothesisStatus.CATALYST_NEAR.allows(HypothesisStatus.REFUTED));
        assertTrue(HypothesisStatus.CONFIRMED.allows(HypothesisStatus.EXPIRED));
        for (var terminal : new HypothesisStatus[]{HypothesisStatus.COMPLETED, HypothesisStatus.REFUTED, HypothesisStatus.EXPIRED}) {
            for (var next : HypothesisStatus.values()) assertFalse(terminal.allows(next));
        }
    }
}
