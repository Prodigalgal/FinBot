package io.omnnu.finbot.application.workflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.workflow.DecimalConditionOperand;
import io.omnnu.finbot.domain.workflow.TextConditionOperand;
import io.omnnu.finbot.domain.workflow.TextListConditionOperand;
import io.omnnu.finbot.domain.workflow.WorkflowCondition;
import io.omnnu.finbot.domain.workflow.WorkflowConditionOperator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkflowConditionEvaluatorTest {
    private static final Map<String, Object> CONTEXT = Map.of(
            "input", Map.of("request_summary", "Analyze BTC"),
            "current", Map.of(
                    "confidence", new BigDecimal("0.72"),
                    "status", "COMPLETED",
                    "evidence_refs", List.of("evidence:1", "evidence:2")));

    @Test
    void evaluatesOnlyStructuredFieldPathsAndTypedOperands() {
        assertTrue(WorkflowConditionEvaluator.evaluate(
                new WorkflowCondition(
                        "current.confidence",
                        WorkflowConditionOperator.GTE,
                        new DecimalConditionOperand(new BigDecimal("0.70"))),
                CONTEXT));
        assertTrue(WorkflowConditionEvaluator.evaluate(
                new WorkflowCondition(
                        "current.status",
                        WorkflowConditionOperator.IN,
                        new TextListConditionOperand(List.of("COMPLETED", "PARTIAL"))),
                CONTEXT));
        assertTrue(WorkflowConditionEvaluator.evaluate(
                new WorkflowCondition(
                        "current.evidence_refs",
                        WorkflowConditionOperator.CONTAINS,
                        new TextConditionOperand("evidence:2")),
                CONTEXT));
    }

    @Test
    void missingOrMalformedFieldsFailClosed() {
        assertFalse(WorkflowConditionEvaluator.evaluate(
                new WorkflowCondition("current.missing", WorkflowConditionOperator.TRUTHY, null),
                CONTEXT));
        assertFalse(WorkflowConditionEvaluator.evaluate(
                new WorkflowCondition(
                        "current.status",
                        WorkflowConditionOperator.GT,
                        new DecimalConditionOperand(BigDecimal.ONE)),
                CONTEXT));
    }
}
