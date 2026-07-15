package io.omnnu.finbot.api.research;

import io.omnnu.finbot.application.research.ResearchCaseView;
import io.omnnu.finbot.application.research.ResearchSegmentationService;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/research/cases")
public final class ResearchCaseController {
    private final ResearchSegmentationService segmentation;

    public ResearchCaseController(ResearchSegmentationService segmentation) {
        this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
    }

    @GetMapping("/by-run/{runId}")
    public ResponseEntity<ResearchCaseView> byRunId(@PathVariable String runId) {
        return segmentation.findByRunId(new WorkflowRunId(runId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
