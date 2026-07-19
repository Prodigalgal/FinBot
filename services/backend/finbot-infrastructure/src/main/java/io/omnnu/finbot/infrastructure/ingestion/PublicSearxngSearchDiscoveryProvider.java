package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.ingestion.PublicSearxngProtocol.DirectorySnapshot;
import io.omnnu.finbot.infrastructure.ingestion.PublicSearxngProtocol.PublicInstance;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
final class PublicSearxngSearchDiscoveryProvider implements SearchDiscoveryProvider {
    private static final URI REGISTRY_URI = URI.create("https://searx.space/data/instances.json");
    private static final int MAXIMUM_DIRECTORY_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_SEARCH_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_INSTANCE_ATTEMPTS = 3;
    private static final Duration DIRECTORY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration GLOBAL_COOLDOWN = Duration.ofHours(1);
    private static final Duration RATE_LIMIT_COOLDOWN = Duration.ofHours(1);
    private static final Duration ACCESS_DENIED_COOLDOWN = Duration.ofHours(24);
    private static final Duration INVALID_RESPONSE_COOLDOWN = Duration.ofHours(24);
    private static final Duration TRANSIENT_FAILURE_COOLDOWN = Duration.ofMinutes(15);

    private final PublicSearxngHttpGateway httpGateway;
    private final PublicSearxngProtocol protocol;
    private final Clock clock;
    private final AtomicInteger selectionCursor = new AtomicInteger();
    private final ConcurrentHashMap<String, Instant> instanceCooldowns = new ConcurrentHashMap<>();
    private final Object directoryLock = new Object();
    private volatile DirectorySnapshot directorySnapshot;
    private volatile Instant poolBlockedUntil = Instant.EPOCH;

    PublicSearxngSearchDiscoveryProvider(
            PublicSearxngHttpGateway httpGateway,
            PublicSearxngProtocol protocol,
            Clock clock) {
        this.httpGateway = Objects.requireNonNull(httpGateway, "httpGateway");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean supports(String provider) {
        return "searxng_public_pool".equalsIgnoreCase(provider);
    }

    @Override
    public List<CollectedPayload> search(InformationSource source, String query) {
        requirePoolSource(source);
        var now = clock.instant();
        if (now.isBefore(poolBlockedUntil)) {
            throw failure(
                    "PUBLIC_SEARXNG_POOL_COOLDOWN",
                    "Public SearXNG pool is cooling down after candidate exhaustion",
                    true,
                    null);
        }
        var directory = directory(source);
        var available = directory.instances().stream()
                .filter(instance -> !isCoolingDown(instance, now))
                .toList();
        if (available.isEmpty()) {
            poolBlockedUntil = now.plus(GLOBAL_COOLDOWN);
            throw failure(
                    directory.instances().isEmpty()
                            ? "PUBLIC_SEARXNG_NO_ELIGIBLE_INSTANCES"
                            : "PUBLIC_SEARXNG_NO_AVAILABLE_INSTANCES",
                    "Public SearXNG pool has no eligible proxied JSON instances",
                    true,
                    null);
        }

        var effectiveQuery = source.defaultQuery(query);
        SourceCollectionException lastFailure = null;
        var attempted = 0;
        for (var instance : rotatedCandidates(available)) {
            attempted++;
            try {
                var result = searchInstance(source, effectiveQuery, directory, instance, attempted);
                instanceCooldowns.remove(instance.key());
                poolBlockedUntil = Instant.EPOCH;
                return result;
            } catch (SourceCollectionException exception) {
                if (exception.errorCode().endsWith("_PROXY_REQUIRED")) {
                    throw exception;
                }
                lastFailure = exception;
                instanceCooldowns.put(instance.key(), now.plus(cooldown(exception)));
            }
        }
        poolBlockedUntil = now.plus(GLOBAL_COOLDOWN);
        throw failure(
                "PUBLIC_SEARXNG_POOL_EXHAUSTED",
                "Public SearXNG pool exhausted " + attempted + " proxied JSON candidates",
                true,
                lastFailure == null ? null : lastFailure.statusCode());
    }

    private DirectorySnapshot directory(InformationSource source) {
        var now = clock.instant();
        var current = directorySnapshot;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current;
        }
        synchronized (directoryLock) {
            current = directorySnapshot;
            if (current != null && now.isBefore(current.expiresAt())) {
                return current;
            }
            try {
                var response = httpGateway.get(new PublicSearxngHttpGateway.Request(
                        source.sourceId().value(),
                        REGISTRY_URI,
                        DIRECTORY_TIMEOUT,
                        MAXIMUM_DIRECTORY_BYTES,
                        2,
                        "PUBLIC_SEARXNG_DIRECTORY",
                        "Public SearXNG directory"));
                if (!PublicSearxngProtocol.isJson(response.contentType())) {
                    throw failure(
                            "PUBLIC_SEARXNG_DIRECTORY_NOT_JSON",
                            "Public SearXNG directory did not return JSON",
                            true,
                            response.statusCode());
                }
                var refreshed = protocol.parseDirectory(response.body(), response.fetchedAt());
                directorySnapshot = refreshed;
                return refreshed;
            } catch (SourceCollectionException exception) {
                if (current != null && now.isBefore(current.staleUntil())) {
                    return current;
                }
                throw exception;
            }
        }
    }

    private List<CollectedPayload> searchInstance(
            InformationSource source,
            String query,
            DirectorySnapshot directory,
            PublicInstance instance,
            int instanceAttempt) {
        var response = httpGateway.get(new PublicSearxngHttpGateway.Request(
                source.sourceId().value(),
                PublicSearxngProtocol.searchUri(instance.baseUri(), query),
                SEARCH_TIMEOUT,
                MAXIMUM_SEARCH_BYTES,
                1,
                "PUBLIC_SEARXNG_INSTANCE",
                "Public SearXNG instance"));
        if (!PublicSearxngProtocol.isJson(response.contentType())) {
            throw failure(
                    "PUBLIC_SEARXNG_INSTANCE_NOT_JSON",
                    "Public SearXNG instance did not expose the JSON Search API",
                    true,
                    response.statusCode());
        }
        return protocol.mapSearch(source, query, directory, instance, instanceAttempt, response);
    }

    private List<PublicInstance> rotatedCandidates(List<PublicInstance> available) {
        var result = new ArrayList<PublicInstance>();
        var limit = Math.min(MAXIMUM_INSTANCE_ATTEMPTS, available.size());
        var offset = Math.floorMod(selectionCursor.getAndIncrement(), available.size());
        for (var index = 0; index < limit; index++) {
            result.add(available.get((offset + index) % available.size()));
        }
        return List.copyOf(result);
    }

    private boolean isCoolingDown(PublicInstance instance, Instant now) {
        var until = instanceCooldowns.get(instance.key());
        if (until == null) {
            return false;
        }
        if (!now.isBefore(until)) {
            instanceCooldowns.remove(instance.key(), until);
            return false;
        }
        return true;
    }

    private static Duration cooldown(SourceCollectionException exception) {
        var statusCode = exception.statusCode();
        if (statusCode != null && statusCode == 429) {
            return RATE_LIMIT_COOLDOWN;
        }
        if (statusCode != null && (statusCode == 400 || statusCode == 401 || statusCode == 403
                || statusCode == 405 || statusCode == 406 || statusCode == 418)) {
            return ACCESS_DENIED_COOLDOWN;
        }
        if (exception.errorCode().endsWith("NOT_JSON")
                || exception.errorCode().endsWith("JSON_INVALID")
                || exception.errorCode().endsWith("SCHEMA_INVALID")) {
            return INVALID_RESPONSE_COOLDOWN;
        }
        return TRANSIENT_FAILURE_COOLDOWN;
    }

    private static void requirePoolSource(InformationSource source) {
        Objects.requireNonNull(source, "source");
        if (!REGISTRY_URI.equals(source.endpointBaseUrl())
                || source.outboundRoute() != OutboundRoute.WEB_CRAWL) {
            throw failure(
                    "PUBLIC_SEARXNG_CONFIGURATION_INVALID",
                    "Public SearXNG pool requires the approved registry and WEB_CRAWL route",
                    true,
                    null);
        }
    }

    private static SourceCollectionException failure(
            String code,
            String message,
            boolean blocked,
            Integer statusCode) {
        return new SourceCollectionException(code, message, blocked, statusCode);
    }
}
