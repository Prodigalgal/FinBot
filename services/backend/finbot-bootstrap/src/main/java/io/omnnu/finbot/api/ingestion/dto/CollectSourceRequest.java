package io.omnnu.finbot.api.ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CollectSourceRequest(
        @NotBlank @Size(max = 1_000) String query,
        @Size(max = 80) String workflowRunId) {
}
