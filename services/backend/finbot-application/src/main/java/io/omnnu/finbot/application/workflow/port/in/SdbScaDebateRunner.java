package io.omnnu.finbot.application.workflow.port.in;

import io.omnnu.finbot.application.workflow.dto.SdbScaDebateResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;

public interface SdbScaDebateRunner {
    SdbScaDebateResult run(WorkflowExecutionContext execution);
}
