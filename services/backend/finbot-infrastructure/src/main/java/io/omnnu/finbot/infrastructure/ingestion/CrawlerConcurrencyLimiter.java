package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class CrawlerConcurrencyLimiter {
    private final Semaphore globalPermits;
    private final int maximumConcurrentPerSource;
    private final int maximumConcurrentPerHost;
    private final Duration waitTimeout;
    private final ConcurrentHashMap<String, Semaphore> sourcePermits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> hostPermits = new ConcurrentHashMap<>();

    public CrawlerConcurrencyLimiter(
            @Value("${finbot.crawler.maximum-concurrent:16}") int maximumConcurrent,
            @Value("${finbot.crawler.maximum-concurrent-per-source:2}") int maximumConcurrentPerSource,
            @Value("${finbot.crawler.maximum-concurrent-per-host:2}") int maximumConcurrentPerHost,
            @Value("${finbot.crawler.permit-wait-timeout:PT10S}") Duration waitTimeout) {
        if (maximumConcurrent < 1 || maximumConcurrent > 256
                || maximumConcurrentPerSource < 1 || maximumConcurrentPerSource > maximumConcurrent
                || maximumConcurrentPerHost < 1 || maximumConcurrentPerHost > maximumConcurrent) {
            throw new IllegalArgumentException("Crawler concurrency limits are invalid");
        }
        this.globalPermits = new Semaphore(maximumConcurrent, true);
        this.maximumConcurrentPerSource = maximumConcurrentPerSource;
        this.maximumConcurrentPerHost = maximumConcurrentPerHost;
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
        if (waitTimeout.isNegative() || waitTimeout.isZero() || waitTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Crawler permit wait timeout is invalid");
        }
    }

    public Permit acquire(String sourceId, URI target) {
        var normalizedSourceId = requireSourceId(sourceId);
        var host = Objects.requireNonNull(target, "target").getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Crawler target has no host");
        }
        var sourceSemaphore = sourcePermits.computeIfAbsent(
                normalizedSourceId,
                ignored -> new Semaphore(maximumConcurrentPerSource, true));
        var hostSemaphore = hostPermits.computeIfAbsent(
                host.toLowerCase(Locale.ROOT),
                ignored -> new Semaphore(maximumConcurrentPerHost, true));
        var globalAcquired = false;
        var sourceAcquired = false;
        try {
            if (!globalPermits.tryAcquire(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw rejected();
            }
            globalAcquired = true;
            if (!sourceSemaphore.tryAcquire(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                globalPermits.release();
                globalAcquired = false;
                throw rejected();
            }
            sourceAcquired = true;
            if (!hostSemaphore.tryAcquire(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                sourceSemaphore.release();
                sourceAcquired = false;
                globalPermits.release();
                globalAcquired = false;
                throw rejected();
            }
            return new Permit(globalPermits, sourceSemaphore, hostSemaphore);
        } catch (InterruptedException exception) {
            if (sourceAcquired) {
                sourceSemaphore.release();
            }
            if (globalAcquired) {
                globalPermits.release();
            }
            Thread.currentThread().interrupt();
            throw new SourceCollectionException(
                    "CRAWLER_PERMIT_INTERRUPTED",
                    "Crawler request permit wait was interrupted",
                    false);
        }
    }

    public Capacity capacity(String sourceId) {
        var normalizedSourceId = requireSourceId(sourceId);
        var sourceSemaphore = sourcePermits.get(normalizedSourceId);
        return new Capacity(
                globalPermits.availablePermits(),
                sourceSemaphore == null ? maximumConcurrentPerSource : sourceSemaphore.availablePermits(),
                maximumConcurrentPerSource);
    }

    private static String requireSourceId(String sourceId) {
        var normalized = Objects.requireNonNull(sourceId, "sourceId").strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("source_[a-z0-9_-]{4,72}")) {
            throw new IllegalArgumentException("Crawler source ID is invalid");
        }
        return normalized;
    }

    private static SourceCollectionException rejected() {
        return new SourceCollectionException(
                "CRAWLER_BACKPRESSURE_REJECTED",
                "Crawler concurrency capacity is exhausted",
                false);
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore globalPermits;
        private final Semaphore sourcePermits;
        private final Semaphore hostPermits;
        private boolean released;

        private Permit(
                Semaphore globalPermits,
                Semaphore sourcePermits,
                Semaphore hostPermits) {
            this.globalPermits = globalPermits;
            this.sourcePermits = sourcePermits;
            this.hostPermits = hostPermits;
        }

        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            released = true;
            hostPermits.release();
            sourcePermits.release();
            globalPermits.release();
        }
    }

    public record Capacity(
            int globalAvailable,
            int sourceAvailable,
            int sourceMaximum) {
        public Capacity {
            if (globalAvailable < 0 || sourceAvailable < 0 || sourceMaximum < 1
                    || sourceAvailable > sourceMaximum) {
                throw new IllegalArgumentException("Crawler capacity snapshot is invalid");
            }
        }

        public boolean saturated() {
            return globalAvailable == 0 || sourceAvailable == 0;
        }
    }
}
