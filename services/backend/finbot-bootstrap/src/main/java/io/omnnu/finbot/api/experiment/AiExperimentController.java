package io.omnnu.finbot.api.experiment;

import io.omnnu.finbot.application.experiment.AiExperiment;
import io.omnnu.finbot.application.experiment.AiExperimentStatus;
import io.omnnu.finbot.application.experiment.AiExperimentUseCase;
import io.omnnu.finbot.application.experiment.SaveAiExperimentCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/ai-experiments")
public final class AiExperimentController {
    private final AiExperimentUseCase useCase;

    public AiExperimentController(AiExperimentUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping
    public List<AiExperiment> list() {
        return useCase.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AiExperiment create(@Valid @RequestBody SaveRequest request) {
        return useCase.save(request.toCommand(null));
    }

    @PutMapping("/{experimentId}")
    public AiExperiment update(
            @PathVariable String experimentId,
            @Valid @RequestBody SaveRequest request) {
        return useCase.save(request.toCommand(experimentId));
    }

    public record SaveRequest(
            @NotBlank @Size(max = 160) String displayName,
            @NotNull AiExperimentStatus status,
            @NotBlank @Size(max = 80) String controlWorkflowVersionId,
            @NotBlank @Size(max = 80) String candidateWorkflowVersionId,
            @Min(1) @Max(9999) int candidateAllocationBasisPoints,
            @NotBlank @Size(max = 80) String evaluationMetric,
            @Min(2) @Max(100000) int minimumSampleSize,
            @Min(0) Long expectedVersion) {
        SaveAiExperimentCommand toCommand(String experimentId) {
            return new SaveAiExperimentCommand(
                    experimentId,
                    displayName,
                    status,
                    controlWorkflowVersionId,
                    candidateWorkflowVersionId,
                    candidateAllocationBasisPoints,
                    evaluationMetric,
                    minimumSampleSize,
                    expectedVersion);
        }
    }
}
