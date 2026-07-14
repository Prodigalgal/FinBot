package io.omnnu.finbot.api.trading;

import io.omnnu.finbot.application.trading.TradeAutomationDetail;
import io.omnnu.finbot.application.trading.TradeAutomationQueryRepository;
import io.omnnu.finbot.application.workflow.WorkflowNotFoundException;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/trading/automations")
public final class TradeAutomationController {
    private final TradeAutomationQueryRepository repository;

    public TradeAutomationController(TradeAutomationQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @GetMapping
    public List<TradeAutomationDetail.Summary> list(
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return repository.list(limit);
    }

    @GetMapping("/{workflowRunId}")
    public TradeAutomationDetail detail(@PathVariable String workflowRunId) {
        return repository.findByWorkflowRunId(new WorkflowRunId(workflowRunId))
                .orElseThrow(() -> new WorkflowNotFoundException(workflowRunId));
    }
}
