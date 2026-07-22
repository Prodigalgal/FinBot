package io.omnnu.finbot.api.setup.controller;

import io.omnnu.finbot.application.setup.dto.SetupProfileApplication;
import io.omnnu.finbot.application.setup.dto.SetupProfileDefinition;
import io.omnnu.finbot.application.setup.dto.SetupProfileId;
import io.omnnu.finbot.application.setup.dto.SetupProfilePreview;
import io.omnnu.finbot.application.setup.port.in.SetupProfileUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/setup-profiles")
public final class SetupProfileController {
    private final SetupProfileUseCase useCase;

    public SetupProfileController(SetupProfileUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping
    public List<SetupProfileDefinition> profiles() {
        return useCase.profiles();
    }

    @GetMapping("/{profileId}/preview")
    public SetupProfilePreview preview(@PathVariable SetupProfileId profileId) {
        return useCase.preview(profileId);
    }

    @PostMapping("/{profileId}/apply")
    public SetupProfileApplication apply(
            @PathVariable SetupProfileId profileId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return useCase.apply(profileId, idempotencyKey);
    }

    @GetMapping("/history")
    public List<SetupProfileApplication> history(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return useCase.history(limit);
    }
}
