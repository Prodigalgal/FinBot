package io.omnnu.finbot.application.workflow.service;

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

class SdbScaIdentityDisclosureGuardTest {
    @Test
    void acceptsAnonymousContentAndRejectsAnyConfiguredIdentity() {
        var guard = new SdbScaIdentityDisclosureGuard(List.of(participant()));
        var anonymous = "{\"summary\":\"需求上行但流动性风险仍高\"}";

        assertEquals(anonymous, guard.requireAnonymous(anonymous));
        assertThrows(
                IllegalArgumentException.class,
                () -> guard.requireAnonymous("{\"summary\":\"来自 node_macro_seat 的判断\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> guard.requireAnonymous("{\"argument\":\"本结论由 Grok-4.5 生成\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> guard.requireAnonymous("{\"argument\":\"我是宏观研究席\"}"));
    }

    private static WorkflowNodeDefinition participant() {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId("node_macro_seat"),
                WorkflowNodeType.AGENT,
                "宏观研究席",
                "宏观研究席",
                null,
                new LogicalRoleKey("macro_role"),
                new AiModelBinding(
                        new AiProviderProfileId("provider_grok_sub2api"),
                        "grok-4.5",
                        ReasoningEffort.MAX),
                null,
                "输出结构化研究判断。",
                "独立研究。",
                WorkflowOutputContract.DEBATE_ARGUMENT,
                WorkflowContextMode.UPSTREAM,
                0,
                8,
                256,
                30,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                null,
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                true);
    }
}
