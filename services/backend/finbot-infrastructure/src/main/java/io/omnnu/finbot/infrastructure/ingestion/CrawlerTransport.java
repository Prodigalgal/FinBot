package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class CrawlerTransport {
    private final RoutedHttpClientFactory httpClients;
    private final CrawlerConcurrencyLimiter concurrencyLimiter;
    private final Clock clock;

    public CrawlerTransport(
            RoutedHttpClientFactory httpClients,
            CrawlerConcurrencyLimiter concurrencyLimiter,
            Clock clock) {
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.concurrencyLimiter = Objects.requireNonNull(concurrencyLimiter, "concurrencyLimiter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @SuppressWarnings("try")
    public Response get(Request request) {
        Objects.requireNonNull(request, "request");
        validateTarget(request);
        for (var attempt = 1; attempt <= request.maximumAttempts(); attempt++) {
            var route = httpClients.route(request.route());
            if (request.requireProxy() && !route.proxied()) {
                throw new SourceCollectionException(
                        request.errorPrefix() + "_PROXY_REQUIRED",
                        request.safeName() + " requires a proxied route",
                        true);
            }
            var builder = HttpRequest.newBuilder(request.target())
                    .timeout(request.timeout())
                    .GET();
            request.headers().forEach(builder::header);
            try {
                Response response;
                try (var permit = concurrencyLimiter.acquire(request.target())) {
                    var httpResponse = httpClients.clientForRequest(route).send(
                            builder.build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    try (var stream = httpResponse.body()) {
                        var body = stream.readNBytes(request.maximumResponseBytes() + 1);
                        if (body.length > request.maximumResponseBytes()) {
                            throw new SourceCollectionException(
                                    request.errorPrefix() + "_RESPONSE_TOO_LARGE",
                                    request.safeName() + " response exceeded the configured safety limit",
                                    false);
                        }
                        response = new Response(
                                request.target(),
                                httpResponse.statusCode(),
                                httpResponse.headers().firstValue("content-type")
                                        .orElse("application/octet-stream"),
                                body,
                                sanitizedHeaders(httpResponse),
                                route.redactedEndpoint(),
                                attempt,
                                clock.instant());
                    }
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (retryable(response.statusCode()) && attempt < request.maximumAttempts()) {
                        pauseBeforeRetry(request, retryDelay(response, attempt));
                        continue;
                    }
                    throw new SourceCollectionException(
                            request.errorPrefix() + "_HTTP_" + response.statusCode(),
                            request.safeName() + " returned HTTP " + response.statusCode(),
                            response.statusCode() == 401 || response.statusCode() == 403);
                }
                return response;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new SourceCollectionException(
                        request.errorPrefix() + "_INTERRUPTED",
                        request.safeName() + " request was interrupted",
                        false);
            } catch (IOException exception) {
                if (attempt < request.maximumAttempts()) {
                    pauseBeforeRetry(request, Duration.ofSeconds(Math.min(4, attempt)));
                    continue;
                }
                throw new SourceCollectionException(
                        request.errorPrefix() + "_NETWORK_FAILURE",
                        request.safeName() + " request failed after " + attempt + " attempts: "
                                + exception.getClass().getSimpleName(),
                        false);
            }
        }
        throw new IllegalStateException("Crawler transport retry loop completed without a result");
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static Duration retryDelay(Response response, int attempt) {
        var retryAfter = response.responseHeaders().getOrDefault("retry-after", "");
        try {
            return Duration.ofSeconds(Math.max(1, Math.min(10, Long.parseLong(retryAfter))));
        } catch (NumberFormatException exception) {
            return Duration.ofSeconds(Math.min(4, attempt));
        }
    }

    private static void pauseBeforeRetry(Request request, Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceCollectionException(
                    request.errorPrefix() + "_INTERRUPTED",
                    request.safeName() + " retry was interrupted",
                    false);
        }
    }

    private static Map<String, String> sanitizedHeaders(HttpResponse<?> response) {
        var result = new HashMap<String, String>();
        response.headers().firstValue("content-type").ifPresent(value -> result.put("content-type", value));
        response.headers().firstValue("etag").ifPresent(value -> result.put("etag", value));
        response.headers().firstValue("last-modified").ifPresent(value -> result.put("last-modified", value));
        response.headers().firstValue("retry-after").ifPresent(value -> result.put("retry-after", value));
        return Map.copyOf(result);
    }

    private static void validateTarget(Request request) {
        var target = request.target();
        var host = target.getHost();
        if (host == null || target.getUserInfo() != null || target.getFragment() != null
                || !("http".equalsIgnoreCase(target.getScheme())
                || "https".equalsIgnoreCase(target.getScheme()))) {
            throw new SourceCollectionException(
                    request.errorPrefix() + "_TARGET_INVALID",
                    request.safeName() + " target URL is not an allowed HTTP address",
                    true);
        }
        try {
            for (var address : InetAddress.getAllByName(host)) {
                if (isPrivateOrLocal(address)) {
                    throw new SourceCollectionException(
                            request.errorPrefix() + "_SSRF_BLOCKED",
                            request.safeName() + " target resolves to a private or local address",
                            true);
                }
            }
        } catch (UnknownHostException ignored) {
            // An unresolved hostname is classified by the HTTP client as a network failure.
        }
    }

    private static boolean isPrivateOrLocal(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(address.getAddress());
    }

    private static boolean isCarrierGradeNat(byte[] bytes) {
        return bytes.length == 4
                && (bytes[0] & 0xff) == 100
                && (bytes[1] & 0xff) >= 64
                && (bytes[1] & 0xff) <= 127;
    }

    public record Request(
            URI target,
            OutboundRoute route,
            Map<String, String> headers,
            Duration timeout,
            int maximumResponseBytes,
            int maximumAttempts,
            boolean requireProxy,
            String errorPrefix,
            String safeName) {
        public Request {
            target = Objects.requireNonNull(target, "target");
            route = Objects.requireNonNull(route, "route");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            timeout = Objects.requireNonNull(timeout, "timeout");
            errorPrefix = requireText(errorPrefix, "errorPrefix");
            safeName = requireText(safeName, "safeName");
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0
                    || maximumResponseBytes < 1 || maximumResponseBytes > 20 * 1024 * 1024
                    || maximumAttempts < 1 || maximumAttempts > 3) {
                throw new IllegalArgumentException("Crawler transport request limits are invalid");
            }
        }

        private static String requireText(String value, String field) {
            var normalized = Objects.requireNonNull(value, field).strip();
            if (normalized.isEmpty() || normalized.length() > 80) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return normalized;
        }
    }

    public record Response(
            URI requestedUrl,
            int statusCode,
            String contentType,
            byte[] body,
            Map<String, String> responseHeaders,
            String proxyRoute,
            int attempts,
            Instant fetchedAt) {
        public Response {
            body = body.clone();
            responseHeaders = Map.copyOf(responseHeaders);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
