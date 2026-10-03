package io.omnnu.finbot.domain.debate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SdbScaSeatPolicyTest {
    @Test
    void acceptsUniqueRolesAndModels() {
        assertDoesNotThrow(() -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "mimo/mimo-v2.5", null, true),
                seat("seat_risk", "risk", "glm/glm-5.3", null, true))));
    }

    @Test
    void acceptsMultipleSeatsForOneRoleWhenModelsDiffer() {
        assertDoesNotThrow(() -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "mimo/mimo-v2.5", null, true),
                seat("seat_macro_alt", "macro", "glm/glm-5.3", null, true))));
    }

    @Test
    void rejectsSameModelAcrossProviderNamespacesAndServingModes() {
        assertThrows(IllegalArgumentException.class, () -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "MiMo/MiMo-V2.5", null, true),
                seat("seat_macro_alt", "macro", "other/mimo-v2.5-high", null, true))));
    }

    @Test
    void rejectsFallbackUsingAnotherSeatsPrimaryModelInTheSameRole() {
        assertThrows(IllegalArgumentException.class, () -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "mimo/mimo-v2.5", "glm/glm-5.3", true),
                seat("seat_macro_alt", "macro", "glm/glm-5.3", null, true))));
    }

    @Test
    void rejectsSharedFallbackModels() {
        assertThrows(IllegalArgumentException.class, () -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "mimo/mimo-v2.5", "longcat/longcat-pro", true),
                seat("seat_macro_alt", "macro", "glm/glm-5.3", "longcat-pro", true))));
    }

    @Test
    void allowsPrivateFallbackAndSameModelThroughAnotherProviderWithinOneSeat() {
        assertDoesNotThrow(() -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "mimo/mimo-v2.5", "mimo-v2.5", true),
                seat("seat_macro_alt", "macro", "glm/glm-5.3", "grok_web/grok-3", true))));
    }

    @Test
    void permitsModelReuseAcrossDifferentRoles() {
        assertDoesNotThrow(() -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "mimo/mimo-v2.5", "glm/glm-5.3", true),
                seat("seat_risk", "risk", "glm/glm-5.3", "mimo/mimo-v2.5", true))));
    }

    @Test
    void ignoresDisabledSeats() {
        assertDoesNotThrow(() -> SdbScaSeatPolicy.validate(List.of(
                seat("seat_macro", "macro", "mimo/mimo-v2.5", null, true),
                seat("seat_macro_alt", "macro", "random", null, false))));
    }

    @Test
    void rejectsOpaqueAndDynamicSelectors() {
        for (var selector : List.of("default", "deepseek/expert", "arena/Max", "auto", "random-text",
                "vendor/model-latest", "grok_web/grok-chat-auto", "arena/anonymous-123")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SdbScaSeatPolicy.canonicalModelName(selector), selector);
        }
    }

    @Test
    void preservesExplicitModelVersions() {
        assertEquals("mimo-v2.5-pro", SdbScaSeatPolicy.canonicalModelName("mimo/mimo-v2.5-pro"));
        assertEquals("mimo-v2.6-pro", SdbScaSeatPolicy.canonicalModelName("mimo/mimo-v2.6-pro"));
    }

    private static WorkflowNodeDefinition seat(
            String id, String role, String primary, String fallback, boolean enabled) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId("node_" + id), WorkflowNodeType.AGENT, id, role, null, new LogicalRoleKey(role),
                binding("provider_primary", primary),
                fallback == null ? null : binding("provider_fallback", fallback),
                "Independent analyst", "Analyze the frozen evidence",
                WorkflowOutputContract.DEBATE_ARGUMENT, WorkflowContextMode.NONE, 0, 8, 256, 30,
                new WorkflowRetryPolicy(1, Duration.ZERO), null,
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO), enabled);
    }

    private static AiModelBinding binding(String provider, String model) {
        return new AiModelBinding(new AiProviderProfileId(provider), model, ReasoningEffort.PROVIDER_DEFAULT);
    }
}
