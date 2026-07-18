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
    private final int maximumConcurrentPerHost;
    private final Duration waitTimeout;
    private final ConcurrentHashMap<String, Semaphore> hostPermits = new ConcurrentHashMap<>();

    public CrawlerConcurrencyLimiter(
            @Value("${finbot.crawler.maximum-concurrent:16}") int maximumConcurrent,
            @Value("${finbot.crawler.maximum-concurrent-per-host:2}") int maximumConcurrentPerHost,
            @Value("${finbot.crawler.permit-wait-timeout:PT10S}") Duration waitTimeout) {
        if (maximumConcurrent < 1 || maximumConcurrent > 256
                || maximumConcurrentPerHost < 1 || maximumConcurrentPerHost > maximumConcurrent) {
            throw new IllegalArgumentException("Crawler concurrency limits are invalid");
        }
        this.globalPermits = new Semaphore(maximumConcurrent, true);
        this.maximumConcurrentPerHost = maximumConcurrentPerHost;
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
        if (waitTimeout.isNegative() || waitTimeout.isZero() || waitTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Crawler permit wait timeout is invalid");
        }
    }

    public Permit acquire(URI target) {
        var host = Objects.requireNonNull(target, "target").getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Crawler target has no host");
        }
        var hostSemaphore = hostPermits.computeIfAbsent(
                host.toLowerCase(Locale.ROOT),
                ignored -> new Semaphore(maximumConcurrentPerHost, true));
        try {
            if (!globalPermits.tryAcquire(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw rejected();
            }
            if (!hostSemaphore.tryAcquire(waitTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                globalPermits.release();
                throw rejected();
            }
            return new Permit(globalPermits, hostSemaphore);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceCollectionException(
                    "CRAWLER_PERMIT_INTERRUPTED",
                    "Crawler request permit wait was interrupted",
                    false);
        }
    }

    private static SourceCollectionException rejected() {
        return new SourceCollectionException(
                "CRAWLER_BACKPRESSURE_REJECTED",
                "Crawler concurrency capacity is exhausted",
                false);
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore globalPermits;
        private final Semaphore hostPermits;
        private boolean released;

        private Permit(Semaphore globalPermits, Semaphore hostPermits) {
            this.globalPermits = globalPermits;
            this.hostPermits = hostPermits;
        }

        @Override
        public synchronized void close() {
            if (released) {
                return;
            }
            released = true;
            hostPermits.release();
            globalPermits.release();
        }
    }
}
