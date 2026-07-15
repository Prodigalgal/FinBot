package io.omnnu.finbot.api.analysis;

import io.omnnu.finbot.api.research.InstantResearchResponse;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.market.MarketAnalysisScope;
import io.omnnu.finbot.application.quant.TradeRiskPreview;
import io.omnnu.finbot.application.quant.TradeRiskPreviewUseCase;
import io.omnnu.finbot.application.research.ResearchLaunchResult;
import io.omnnu.finbot.application.research.ResearchLaunchUseCase;
import io.omnnu.finbot.application.shared.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public final class AnalysisController {
    private final ResearchLaunchUseCase researchLaunch;
    private final TradeRiskPreviewUseCase riskPreview;

    public AnalysisController(
            ResearchLaunchUseCase researchLaunch,
            TradeRiskPreviewUseCase riskPreview) {
        this.researchLaunch = Objects.requireNonNull(researchLaunch, "researchLaunch");
        this.riskPreview = Objects.requireNonNull(riskPreview, "riskPreview");
    }

    @PostMapping("/analysis/market-runs")
    public CompletionStage<ResponseEntity<InstantResearchResponse>> startMarketAnalysis(
            @RequestHeader("Idempotency-Key") String clientIdempotencyKey,
            @Valid @RequestBody MarketAnalysisRequest request) {
        var operationKey = IdempotencyKeys.scoped("market-analysis", clientIdempotencyKey);
        var versionId = request.workflowVersionId() == null || request.workflowVersionId().isBlank()
                ? null
                : new WorkflowVersionId(request.workflowVersionId());
        var summary = "市场分析：" + request.exchange().toUpperCase(java.util.Locale.ROOT)
                + ' ' + request.symbol().toUpperCase(java.util.Locale.ROOT)
                + "，K 线周期 " + request.intervalSeconds() + " 秒，预测期限 "
                + request.forecastHorizonSeconds() + " 秒。"
                + request.question().strip();
        var command = new StartWorkflowCommand(
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.API,
                versionId,
                summary,
                operationKey);
        var scope = new MarketAnalysisScope(
                new io.omnnu.finbot.domain.catalog.InstrumentId(request.instrumentId()),
                request.symbol(),
                ExchangeVenue.valueOf(request.exchange().toUpperCase(java.util.Locale.ROOT)),
                request.intervalSeconds(),
                request.forecastHorizonSeconds());
        return researchLaunch.launch(command, operationKey, ResearchTaskMode.STANDARD, scope)
                .thenApply(AnalysisController::accepted);
    }

    @PostMapping("/quant/previews")
    public TradeRiskPreview preview(@Valid @RequestBody TradeRiskPreviewRequest request) {
        return riskPreview.preview(request.toCommand());
    }

    private static ResponseEntity<InstantResearchResponse> accepted(ResearchLaunchResult launched) {
        var body = InstantResearchResponse.from(launched, WorkflowRunStatus.ACCEPTED);
        return ResponseEntity.accepted().location(URI.create(body.statusUrl())).body(body);
    }

    public record MarketAnalysisRequest(
            @NotBlank @Size(max = 80) String instrumentId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,48}$") String symbol,
            @NotBlank @Pattern(regexp = "(?i)^(GATE|BYBIT)$") String exchange,
            @Min(60) @Max(604800) int intervalSeconds,
            @Min(60) @Max(31536000) int forecastHorizonSeconds,
            @NotBlank @Size(max = 1500) String question,
            @Size(max = 80) String workflowVersionId) {
    }
}
