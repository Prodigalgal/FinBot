package io.omnnu.finbot.api.research;

import io.omnnu.finbot.application.review.ResearchComparison;
import io.omnnu.finbot.application.review.ResearchEffectiveness;
import io.omnnu.finbot.application.review.ResearchFeedback;
import io.omnnu.finbot.application.review.ResearchFeedbackRating;
import io.omnnu.finbot.application.review.ResearchReviewUseCase;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/research/review")
public final class ResearchReviewController {
    private final ResearchReviewUseCase useCase;

    public ResearchReviewController(ResearchReviewUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping("/compare")
    public ResearchComparison compare(
            @RequestParam String leftRunId,
            @RequestParam String rightRunId) {
        return useCase.compare(new WorkflowRunId(leftRunId), new WorkflowRunId(rightRunId));
    }

    @GetMapping("/feedback")
    public List<ResearchFeedback> feedback(
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return useCase.feedback(limit);
    }

    @PutMapping("/{runId}/feedback")
    public ResearchFeedback saveFeedback(
            @PathVariable String runId,
            @Valid @RequestBody FeedbackRequest request) {
        return useCase.saveFeedback(
                new WorkflowRunId(runId),
                request.rating(),
                request.effectiveness(),
                request.note(),
                request.expectedVersion());
    }

    public record FeedbackRequest(
            @NotNull ResearchFeedbackRating rating,
            @NotNull ResearchEffectiveness effectiveness,
            @Size(max = 2000) String note,
            @Min(0) Long expectedVersion) {
    }
}
