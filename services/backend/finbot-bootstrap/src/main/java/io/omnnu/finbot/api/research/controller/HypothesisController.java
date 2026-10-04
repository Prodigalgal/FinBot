package io.omnnu.finbot.api.research.controller;

import io.omnnu.finbot.application.research.dto.HypothesisView;
import io.omnnu.finbot.application.research.dto.HypothesisRevision;
import io.omnnu.finbot.application.research.port.in.HypothesisUseCase;
import io.omnnu.finbot.domain.research.HypothesisStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v2/research/hypotheses")
public final class HypothesisController {
    private final HypothesisUseCase hypotheses;
    public HypothesisController(HypothesisUseCase hypotheses) { this.hypotheses = Objects.requireNonNull(hypotheses, "hypotheses"); }
    @GetMapping public List<HypothesisView> recent(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return hypotheses.recent(limit);
    }
    @GetMapping("/{hypothesisId}/history") public List<HypothesisRevision> history(@PathVariable String hypothesisId) {
        return hypotheses.history(hypothesisId);
    }
    @PutMapping("/{hypothesisId}/status") public HypothesisView transition(@PathVariable String hypothesisId, @Valid @RequestBody StatusRequest request) {
        return hypotheses.transition(hypothesisId, request.expectedVersion(), request.status(), request.reason());
    }
    public record StatusRequest(@NotNull @Min(0) Long expectedVersion, @NotNull HypothesisStatus status,
                                @NotBlank @Size(max = 2000) String reason) { }
}
