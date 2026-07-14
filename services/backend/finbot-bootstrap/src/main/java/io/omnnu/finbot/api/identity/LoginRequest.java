package io.omnnu.finbot.api.identity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 500) String password,
        @NotBlank @Size(max = 80) String challengeId,
        @NotBlank @Size(max = 200) String proofOfWorkSolution,
        @Min(-1000) @Max(1000) int mathAnswer) {
}
