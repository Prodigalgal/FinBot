package io.omnnu.finbot.application.quant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

final class QuantAnalysisCapabilitiesTest {
    @Test
    void publishesUniqueStrategyAndIndicatorIdentifiersForWorkflowContext() {
        var strategyIds = QuantAnalysisCapabilities.strategies().stream()
                .map(QuantAnalysisCapabilities.Capability::id)
                .toList();
        var indicatorIds = QuantAnalysisCapabilities.indicators().stream()
                .map(QuantAnalysisCapabilities.Capability::id)
                .toList();

        assertEquals(strategyIds.size(), new HashSet<>(strategyIds).size());
        assertEquals(indicatorIds.size(), new HashSet<>(indicatorIds).size());
        assertTrue(strategyIds.contains("multi_strategy_ensemble"));
        assertTrue(indicatorIds.contains("macd_histogram"));
        assertTrue(indicatorIds.contains("golden_cross_event_50_200"));
        assertTrue(indicatorIds.contains("support_level_20"));
        assertTrue(indicatorIds.contains("resistance_level_20"));
    }
}
