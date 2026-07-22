package io.omnnu.finbot.application.trading.port.out;

import io.omnnu.finbot.application.trading.dto.TradeAutomationDetail;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;
import java.util.Optional;

public interface TradeAutomationQueryRepository {
    List<TradeAutomationDetail.Summary> list(int limit);

    Optional<TradeAutomationDetail> findByWorkflowRunId(WorkflowRunId workflowRunId);
}
