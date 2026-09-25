package io.omnnu.finbot.api.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAnalysisChatRequest(
        @NotBlank @Size(max = 80) String workflowVersionId) {
}
