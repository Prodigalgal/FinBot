package io.omnnu.finbot.api.ingestion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TestSourceRequest(@NotBlank @Size(max = 1_000) String query) {
}
