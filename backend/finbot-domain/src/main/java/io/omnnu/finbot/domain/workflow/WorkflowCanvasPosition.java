package io.omnnu.finbot.domain.workflow;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;

public record WorkflowCanvasPosition(BigDecimal x, BigDecimal y) {
    public WorkflowCanvasPosition {
        x = DecimalValue.finite(x, "x");
        y = DecimalValue.finite(y, "y");
    }
}
