package io.omnnu.finbot.application.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import org.junit.jupiter.api.Test;

class TradeAutomationApplicationServiceTest {
    @Test
    void createsContractValidNodesForBothExecutionStages() {
        var draft = TradeAutomationApplicationService.executionNode(stage(TradeExecutionAiStage.DRAFT));
        var reflection = TradeAutomationApplicationService.executionNode(stage(TradeExecutionAiStage.REFLECTION));

        assertEquals("node_execution_draft", draft.nodeId().value());
        assertEquals(WorkflowNodeType.EXECUTION_REVIEW, draft.nodeType());
        assertEquals(WorkflowOutputContract.TRADE_DECISIONS, draft.outputContract());
        assertEquals("node_execution_reflection", reflection.nodeId().value());
        assertEquals(WorkflowNodeType.EXECUTION_REVIEW, reflection.nodeType());
        assertEquals(WorkflowOutputContract.EXECUTION_VERDICT, reflection.outputContract());
    }

    private static TradeExecutionAiStageConfig stage(TradeExecutionAiStage stage) {
        return new TradeExecutionAiStageConfig(
                stage,
                new AiProviderProfileId("provider_sub2api_default"),
                "gpt-5.6-sol",
                ReasoningEffort.MAX,
                "System prompt",
                "User prompt",
                4_096,
                300,
                true,
                0);
    }
}
