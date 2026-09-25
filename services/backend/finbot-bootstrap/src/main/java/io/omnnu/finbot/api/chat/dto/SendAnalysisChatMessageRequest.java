package io.omnnu.finbot.api.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendAnalysisChatMessageRequest(
        @NotBlank @Size(max = 2_000) String message) {
}
