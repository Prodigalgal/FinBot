package io.omnnu.finbot.api.identity;

import io.omnnu.finbot.application.identity.AdminApiTokenUseCase;
import io.omnnu.finbot.application.identity.CreateAdminApiTokenCommand;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v2/api-tokens")
public class AdminApiTokenController {
    private final AdminApiTokenUseCase useCase;
    private final Clock clock;

    public AdminApiTokenController(AdminApiTokenUseCase useCase, Clock clock) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public List<AdminApiTokenResponse> list() {
        var now = clock.instant();
        return useCase.listTokens().stream()
                .map(token -> AdminApiTokenResponse.from(token, now))
                .toList();
    }

    @PostMapping
    public ResponseEntity<CreatedAdminApiTokenResponse> create(
            @Valid @RequestBody CreateAdminApiTokenRequest request,
            Authentication authentication) {
        var now = clock.instant();
        var created = useCase.createToken(new CreateAdminApiTokenCommand(
                request.displayName(),
                request.expiresInDays(),
                authentication.getName()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(CreatedAdminApiTokenResponse.from(created, now));
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable String tokenId,
            @RequestParam @PositiveOrZero long expectedVersion) {
        useCase.revokeToken(new AdminApiTokenId(tokenId), expectedVersion);
    }
}
