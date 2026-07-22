package io.omnnu.finbot.api.exchange.controller;

import io.omnnu.finbot.application.exchange.dto.ExchangeAccountControl;
import io.omnnu.finbot.application.exchange.port.in.ExchangeAccountControlUseCase;
import io.omnnu.finbot.application.exchange.dto.ExchangeAccountSyncResult;
import io.omnnu.finbot.application.exchange.port.in.ExchangeAccountSyncUseCase;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/trading/accounts")
public final class ExchangeAccountControlController {
    private final ExchangeAccountControlUseCase useCase;
    private final ExchangeAccountSyncUseCase syncUseCase;

    public ExchangeAccountControlController(
            ExchangeAccountControlUseCase useCase,
            ExchangeAccountSyncUseCase syncUseCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.syncUseCase = Objects.requireNonNull(syncUseCase, "syncUseCase");
    }

    @PutMapping("/{accountId}/configuration")
    public AccountControlResponse setEnabled(
            @PathVariable String accountId,
            @Valid @RequestBody UpdateAccountControlRequest request) {
        return AccountControlResponse.from(useCase.setEnabled(
                new ExchangeAccountId(accountId),
                request.enabled(),
                request.expectedVersion()));
    }

    @PostMapping("/{accountId}/test")
    public CompletionStage<ExchangeAccountSyncResult> test(@PathVariable String accountId) {
        return syncUseCase.synchronize(new ExchangeAccountId(accountId));
    }

    public record UpdateAccountControlRequest(
            boolean enabled,
            @PositiveOrZero long expectedVersion) {
    }

    public record AccountControlResponse(
            String accountId,
            ExchangeVenue exchange,
            ExchangeEnvironment environment,
            String displayName,
            boolean enabled,
            long version,
            Instant updatedAt) {
        static AccountControlResponse from(ExchangeAccountControl account) {
            return new AccountControlResponse(
                    account.accountId().value(),
                    account.exchange(),
                    account.environment(),
                    account.displayName(),
                    account.enabled(),
                    account.version(),
                    account.updatedAt());
        }
    }
}
