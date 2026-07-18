package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One-token per-host scheduler used to prevent crawler request bursts. */
@Component
public final class CrawlerPolitenessController {
    private final Duration minimumHostDelay;
    private final Clock clock;
    private final ConcurrentHashMap<String, HostSchedule> schedules = new ConcurrentHashMap<>();

    public CrawlerPolitenessController(
            @Value("${finbot.crawler.minimum-host-delay:PT0.25S}") Duration minimumHostDelay,
            Clock clock) {
        this.minimumHostDelay = Objects.requireNonNull(minimumHostDelay, "minimumHostDelay");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (minimumHostDelay.isNegative() || minimumHostDelay.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Crawler minimum host delay is invalid");
        }
    }

    public void await(URI target) {
        var delay = reserveDelay(target);
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceCollectionException(
                    "CRAWLER_RATE_LIMIT_INTERRUPTED",
                    "Crawler host rate-limit wait was interrupted",
                    false);
        }
    }

    Duration reserveDelay(URI target) {
        var host = Objects.requireNonNull(target, "target").getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Crawler target has no host");
        }
        var now = clock.instant();
        var schedule = schedules.computeIfAbsent(
                host.toLowerCase(Locale.ROOT),
                ignored -> new HostSchedule());
        synchronized (schedule) {
            var reservedAt = schedule.nextAllowedAt.isAfter(now) ? schedule.nextAllowedAt : now;
            schedule.nextAllowedAt = reservedAt.plus(minimumHostDelay);
            return Duration.between(now, reservedAt);
        }
    }

    public RateLimitStatus status() {
        return new RateLimitStatus(minimumHostDelay, schedules.size());
    }

    private static final class HostSchedule {
        private Instant nextAllowedAt = Instant.EPOCH;
    }

    public record RateLimitStatus(Duration minimumHostDelay, int trackedHostCount) {
        public RateLimitStatus {
            Objects.requireNonNull(minimumHostDelay, "minimumHostDelay");
            if (trackedHostCount < 0) {
                throw new IllegalArgumentException("Tracked host count must not be negative");
            }
        }
    }
}
