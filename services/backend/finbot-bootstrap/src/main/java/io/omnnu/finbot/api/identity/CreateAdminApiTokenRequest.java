package io.omnnu.finbot.api.identity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAdminApiTokenRequest(
        @NotBlank @Size(max = 120) String displayName,
        @Min(1) @Max(3650) Integer expiresInDays) {
}
