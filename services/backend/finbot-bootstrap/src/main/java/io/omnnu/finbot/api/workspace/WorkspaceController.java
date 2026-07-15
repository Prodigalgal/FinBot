package io.omnnu.finbot.api.workspace;

import io.omnnu.finbot.application.workspace.IngestionWorkspace;
import io.omnnu.finbot.application.workspace.NetworkWorkspace;
import io.omnnu.finbot.application.workspace.OperationsReport;
import io.omnnu.finbot.application.workspace.PlatformReadiness;
import io.omnnu.finbot.application.workspace.PlatformWorkspaceUseCase;
import io.omnnu.finbot.application.workspace.QuantWorkspace;
import io.omnnu.finbot.application.workspace.WorkflowLearning;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public final class WorkspaceController {
    private final PlatformWorkspaceUseCase useCase;
    private final Clock clock;

    public WorkspaceController(PlatformWorkspaceUseCase useCase, Clock clock) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping("/readiness")
    public PlatformReadiness readiness() {
        return useCase.readiness();
    }

    @GetMapping("/ingestion/workspace")
    public IngestionWorkspace ingestion(
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return useCase.ingestion(limit);
    }

    @GetMapping("/quant/runs")
    public QuantWorkspace quant(
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return useCase.quant(limit);
    }

    @GetMapping("/reports")
    public OperationsReport report(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        var toExclusive = to == null ? clock.instant().plus(1, ChronoUnit.SECONDS) : to;
        var fromInclusive = from == null ? toExclusive.minus(30, ChronoUnit.DAYS) : from;
        return useCase.report(fromInclusive, toExclusive);
    }

    @GetMapping("/network")
    public NetworkWorkspace network() {
        return useCase.network();
    }

    @GetMapping("/workflow-versions/{versionId}/learning")
    public WorkflowLearning workflowLearning(@PathVariable String versionId) {
        return useCase.workflowLearning(new WorkflowVersionId(versionId));
    }
}
