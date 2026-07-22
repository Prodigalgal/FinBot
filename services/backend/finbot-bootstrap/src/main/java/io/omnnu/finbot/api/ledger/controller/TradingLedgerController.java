package io.omnnu.finbot.api.ledger.controller;

import io.omnnu.finbot.api.ledger.dto.TradingRangePreset;
import io.omnnu.finbot.api.ledger.dto.TradingResponses;

import io.omnnu.finbot.api.ledger.dto.TradingResponses.AccountsOverviewResponse;
import io.omnnu.finbot.api.ledger.dto.TradingResponses.ActivityPageResponse;
import io.omnnu.finbot.api.ledger.dto.TradingResponses.PositionResponse;
import io.omnnu.finbot.application.ledger.dto.TradingActivityCriteria;
import io.omnnu.finbot.application.ledger.dto.TradingActivityCursor;
import io.omnnu.finbot.application.ledger.port.in.TradingLedgerQueryUseCase;
import io.omnnu.finbot.application.ledger.dto.TradingTimeRange;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.TradingActivityType;
import io.omnnu.finbot.domain.ledger.TradingActivitySource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/trading")
public final class TradingLedgerController {
    private static final Instant ALL_HISTORY_START = Instant.parse("2000-01-01T00:00:00Z");

    private final TradingLedgerQueryUseCase queryUseCase;
    private final Clock clock;

    public TradingLedgerController(TradingLedgerQueryUseCase queryUseCase, Clock clock) {
        this.queryUseCase = Objects.requireNonNull(queryUseCase, "queryUseCase");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping("/accounts")
    public AccountsOverviewResponse accounts(
            @RequestParam(defaultValue = "ALL") TradingRangePreset range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return AccountsOverviewResponse.from(queryUseCase.accounts(resolveRange(range, from, to)));
    }

    @GetMapping("/accounts/{accountId}/positions")
    public List<PositionResponse> currentPositions(@PathVariable String accountId) {
        return queryUseCase.currentPositions(new ExchangeAccountId(accountId)).stream()
                .map(PositionResponse::from)
                .toList();
    }

    @GetMapping("/activity")
    public ActivityPageResponse activity(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) TradingActivitySource source,
            @RequestParam(required = false) TradingActivityType activityType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "DAYS_30") TradingRangePreset range,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant beforeOccurredAt,
            @RequestParam(required = false) String beforeActivityId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        var cursor = cursor(beforeOccurredAt, beforeActivityId);
        var criteria = new TradingActivityCriteria(
                accountId == null || accountId.isBlank() ? null : new ExchangeAccountId(accountId),
                source,
                activityType,
                status,
                symbol,
                resolveRange(range, from, to),
                cursor,
                limit);
        return ActivityPageResponse.from(queryUseCase.activity(criteria));
    }

    private TradingTimeRange resolveRange(TradingRangePreset preset, Instant from, Instant to) {
        var now = clock.instant();
        return switch (preset) {
            case ALL -> new TradingTimeRange(ALL_HISTORY_START, to == null ? now.plusNanos(1) : to);
            case HOURS_24 -> new TradingTimeRange(now.minus(Duration.ofHours(24)), now.plusNanos(1));
            case DAYS_7 -> new TradingTimeRange(now.minus(Duration.ofDays(7)), now.plusNanos(1));
            case DAYS_30 -> new TradingTimeRange(now.minus(Duration.ofDays(30)), now.plusNanos(1));
            case CUSTOM -> {
                if (from == null || to == null) {
                    throw new IllegalArgumentException("CUSTOM range requires both from and to");
                }
                yield new TradingTimeRange(from, to);
            }
        };
    }

    private static TradingActivityCursor cursor(Instant occurredAt, String activityId) {
        if (occurredAt == null && (activityId == null || activityId.isBlank())) {
            return null;
        }
        if (occurredAt == null || activityId == null || activityId.isBlank()) {
            throw new IllegalArgumentException("Both beforeOccurredAt and beforeActivityId are required");
        }
        return new TradingActivityCursor(occurredAt, activityId);
    }
}
