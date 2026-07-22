package io.omnnu.finbot.api.ingestion.controller;

import io.omnnu.finbot.api.ingestion.dto.CrawlerHeaderProfileMutationRequest;
import io.omnnu.finbot.api.ingestion.dto.CrawlerHeaderProfileResponse;

import io.omnnu.finbot.application.ingestion.port.in.CrawlerHeaderProfileUseCase;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/crawler/header-profiles")
public final class CrawlerHeaderProfileController {
    private final CrawlerHeaderProfileUseCase useCase;

    public CrawlerHeaderProfileController(CrawlerHeaderProfileUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping
    public List<CrawlerHeaderProfileResponse> list() {
        return useCase.listProfiles().stream().map(CrawlerHeaderProfileResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CrawlerHeaderProfileResponse create(
            @Valid @RequestBody CrawlerHeaderProfileMutationRequest request) {
        return CrawlerHeaderProfileResponse.from(useCase.createProfile(request.toDefinition()));
    }

    @PutMapping("/{profileId}")
    public CrawlerHeaderProfileResponse update(
            @PathVariable String profileId,
            @RequestParam long expectedVersion,
            @Valid @RequestBody CrawlerHeaderProfileMutationRequest request) {
        return CrawlerHeaderProfileResponse.from(useCase.updateProfile(
                new CrawlerHeaderProfileId(profileId),
                request.toDefinition(),
                expectedVersion));
    }

    @DeleteMapping("/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable String profileId,
            @RequestParam long expectedVersion) {
        useCase.deleteProfile(new CrawlerHeaderProfileId(profileId), expectedVersion);
    }
}
