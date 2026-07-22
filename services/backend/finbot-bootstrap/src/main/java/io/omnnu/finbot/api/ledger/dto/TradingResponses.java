package io.omnnu.finbot.api.ledger.dto;

import io.omnnu.finbot.application.configuration.dto.RuntimeSecretSource;
import io.omnnu.finbot.application.ledger.dto.AccountOverviewItem;
import io.omnnu.finbot.application.ledger.dto.PositionView;
import io.omnnu.finbot.application.ledger.dto.TradingAccountsOverview;
import io.omnnu.finbot.application.ledger.dto.TradingActivity;
import io.omnnu.finbot.application.ledger.dto.TradingActivityPage;
import io.omnnu.finbot.application.ledger.dto.TradingActivityCount;
import io.omnnu.finbot.application.ledger.dto.TradingActivitySourceStatus;
import io.omnnu.finbot.application.ledger.dto.TradingTimeRange;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.ledger.PositionSide;
import io.omnnu.finbot.domain.ledger.TradingActivitySource;
import io.omnnu.finbot.domain.ledger.TradingActivityType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TradingResponses {
    private TradingResponses() {
    }

    public record TimeRangeResponse(Instant fromInclusive, Instant toExclusive) {
        static TimeRangeResponse from(TradingTimeRange range) {
            return new TimeRangeResponse(range.fromInclusive(), range.toExclusive());
        }
    }

    public record AccountsOverviewResponse(
            TimeRangeResponse range,
            String currency,
            BigDecimal totalEquity,
            BigDecimal totalAvailableBalance,
            BigDecimal totalMarginBalance,
            BigDecimal totalUnrealizedPnl,
            BigDecimal totalRealizedPnl,
            List<AccountResponse> accounts,
            Instant generatedAt) {
        public static AccountsOverviewResponse from(TradingAccountsOverview overview) {
            return new AccountsOverviewResponse(
                    TimeRangeResponse.from(overview.range()),
                    overview.currency(),
                    overview.totalEquity(),
                    overview.totalAvailableBalance(),
                    overview.totalMarginBalance(),
                    overview.totalUnrealizedPnl(),
                    overview.totalRealizedPnl(),
                    overview.accounts().stream().map(AccountResponse::from).toList(),
                    overview.generatedAt());
        }
    }

    public record AccountResponse(
            String accountId,
            ExchangeVenue exchange,
            ExchangeEnvironment environment,
            String displayName,
            String proxyRoute,
            boolean enabled,
            long version,
            boolean credentialConfigured,
            RuntimeSecretSource apiKeySource,
            String apiKeyFingerprint,
            long apiKeyVersion,
            RuntimeSecretSource apiSecretSource,
            String apiSecretFingerprint,
            long apiSecretVersion,
            String dataStatus,
            String currency,
            BigDecimal equity,
            BigDecimal availableBalance,
            BigDecimal marginBalance,
            BigDecimal unrealizedPnl,
            BigDecimal realizedPnl,
            int openPositionCount,
            Instant snapshotAt) {
        static AccountResponse from(AccountOverviewItem account) {
            return new AccountResponse(
                    account.accountId().value(),
                    account.exchange(),
                    account.environment(),
                    account.displayName(),
                    account.proxyRoute(),
                    account.enabled(),
                    account.version(),
                    account.credentialConfigured(),
                    account.apiKeySource(),
                    account.apiKeyFingerprint(),
                    account.apiKeyVersion(),
                    account.apiSecretSource(),
                    account.apiSecretFingerprint(),
                    account.apiSecretVersion(),
                    account.dataStatus().name(),
                    account.currency(),
                    account.equity(),
                    account.availableBalance(),
                    account.marginBalance(),
                    account.unrealizedPnl(),
                    account.realizedPnl(),
                    account.openPositionCount(),
                    account.snapshotAt());
        }
    }

    public record PositionResponse(
            String accountId,
            String symbol,
            PositionSide side,
            BigDecimal quantity,
            BigDecimal entryPrice,
            BigDecimal markPrice,
            BigDecimal liquidationPrice,
            BigDecimal leverage,
            BigDecimal unrealizedPnl,
            BigDecimal margin,
            Instant occurredAt) {
        public static PositionResponse from(PositionView position) {
            return new PositionResponse(
                    position.accountId().value(),
                    position.symbol(),
                    position.side(),
                    position.quantity(),
                    position.entryPrice(),
                    position.markPrice(),
                    position.liquidationPrice(),
                    position.leverage(),
                    position.unrealizedPnl(),
                    position.margin(),
                    position.occurredAt());
        }
    }

    public record ActivityPageResponse(
            List<ActivityResponse> activities,
            ActivityCursorResponse nextCursor,
            long matchedCount,
            List<ActivityCountResponse> counts,
            List<ActivitySourceStatusResponse> sources) {
        public static ActivityPageResponse from(TradingActivityPage page) {
            return new ActivityPageResponse(
                    page.activities().stream().map(ActivityResponse::from).toList(),
                    page.nextCursor() == null
                            ? null
                            : new ActivityCursorResponse(
                                    page.nextCursor().occurredAt(),
                                    page.nextCursor().activityId()),
                    page.matchedCount(),
                    page.counts().stream().map(ActivityCountResponse::from).toList(),
                    page.sources().stream().map(ActivitySourceStatusResponse::from).toList());
        }
    }

    public record ActivityCursorResponse(Instant occurredAt, String activityId) {
    }

    public record ActivityResponse(
            String activityId,
            String sourceEventId,
            TradingActivityType activityType,
            TradingActivitySource source,
            String accountId,
            ExchangeVenue exchange,
            String symbol,
            String status,
            String side,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal amount,
            String currency,
            String exchangeOrderId,
            String clientOrderId,
            String title,
            String detail,
            String detailsJson,
            Instant occurredAt,
            Instant receivedAt) {
        static ActivityResponse from(TradingActivity activity) {
            return new ActivityResponse(
                    activity.activityId(),
                    activity.sourceEventId(),
                    activity.activityType(),
                    activity.source(),
                    activity.accountId() == null ? null : activity.accountId().value(),
                    activity.exchange(),
                    activity.symbol(),
                    activity.status(),
                    activity.side(),
                    activity.quantity(),
                    activity.price(),
                    activity.amount(),
                    activity.currency(),
                    activity.exchangeOrderId(),
                    activity.clientOrderId(),
                    activity.title(),
                    activity.detail(),
                    activity.detailsJson(),
                    activity.occurredAt(),
                    activity.receivedAt());
        }
    }

    public record ActivityCountResponse(TradingActivityType activityType, long count) {
        static ActivityCountResponse from(TradingActivityCount count) {
            return new ActivityCountResponse(count.activityType(), count.count());
        }
    }

    public record ActivitySourceStatusResponse(
            TradingActivitySource source,
            String accountId,
            ExchangeVenue exchange,
            String status,
            boolean complete,
            String message,
            Instant latestAt) {
        static ActivitySourceStatusResponse from(TradingActivitySourceStatus status) {
            return new ActivitySourceStatusResponse(
                    status.source(),
                    status.accountId() == null ? null : status.accountId().value(),
                    status.exchange(),
                    status.status(),
                    status.complete(),
                    status.message(),
                    status.latestAt());
        }
    }
}
