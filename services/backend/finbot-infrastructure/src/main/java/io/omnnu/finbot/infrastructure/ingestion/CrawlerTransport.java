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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class CrawlerTransport {
    private static final int MAXIMUM_REDIRECTS = 5;
    private final RoutedHttpClientFactory httpClients;
    private final CrawlerConcurrencyLimiter concurrencyLimiter;
    private final CrawlerPolitenessController politenessController;
    private final Clock clock;
    private final String userAgent;

    public CrawlerTransport(
            RoutedHttpClientFactory httpClients,
            CrawlerConcurrencyLimiter concurrencyLimiter,
            CrawlerPolitenessController politenessController,
            Clock clock,
            @Value("${finbot.crawler.user-agent:FinBot/2.0 (contact: finbot@omnnu.xyz)}")
                    String userAgent) {
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.concurrencyLimiter = Objects.requireNonNull(concurrencyLimiter, "concurrencyLimiter");
        this.politenessController = Objects.requireNonNull(politenessController, "politenessController");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.userAgent = requireHeaderValue(userAgent, "userAgent", 500);
    }

    @SuppressWarnings("try")
    public Response get(Request request) {
        Objects.requireNonNull(request, "request");
        var target = request.target();
        var headers = withUserAgent(request.headers());
        var redirects = 0;
        var totalAttempts = 0;
        while (true) {
            validateTarget(target, request);
            var response = execute(request, target, headers);
            totalAttempts += response.attempts();
            if (!isRedirect(response.statusCode())) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new SourceCollectionException(
                            request.errorPrefix() + "_HTTP_" + response.statusCode(),
                            request.safeName() + " returned HTTP " + response.statusCode(),
                            response.statusCode() == 401 || response.statusCode() == 403
                                    || response.statusCode() == 429,
                            response.statusCode());
                }
                return new Response(
                        response.requestedUrl(),
                        response.statusCode(),
                        response.contentType(),
                        response.body(),
                        response.responseHeaders(),
                        null,
                        response.proxyRoute(),
                        totalAttempts,
                        redirects,
                        response.fetchedAt());
            }
            if (redirects >= MAXIMUM_REDIRECTS) {
                throw new SourceCollectionException(
                        request.errorPrefix() + "_REDIRECT_LIMIT_EXCEEDED",
                        request.safeName() + " exceeded the redirect safety limit",
                        true);
            }
            var nextTarget = redirectTarget(target, response, request);
            if (!sameOrigin(target, nextTarget)) {
                headers = safeRedirectHeaders(headers);
            }
            target = nextTarget;
            redirects++;
        }
    }

    @SuppressWarnings("try")
    private Response execute(Request request, URI target, Map<String, String> headers) {
        for (var attempt = 1; attempt <= request.maximumAttempts(); attempt++) {
            var route = httpClients.route(request.route());
            if (request.requireProxy() && !route.proxied()) {
                throw new SourceCollectionException(
                        request.errorPrefix() + "_PROXY_REQUIRED",
                        request.safeName() + " requires a proxied route",
                        true);
            }
            var builder = HttpRequest.newBuilder(target)
                    .timeout(request.timeout())
                    .GET();
            headers.forEach(builder::header);
            try {
                Response response;
                try (var permit = concurrencyLimiter.acquire(request.sourceId(), target)) {
                    politenessController.await(target);
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
                                target,
                                httpResponse.statusCode(),
                                httpResponse.headers().firstValue("content-type")
                                        .orElse("application/octet-stream"),
                                body,
                                sanitizedHeaders(httpResponse),
                                httpResponse.headers().firstValue("location").orElse(null),
                                route.redactedEndpoint(),
                                attempt,
                                0,
                                clock.instant());
                    }
                }
                if (!isRedirect(response.statusCode())
                        && (response.statusCode() < 200 || response.statusCode() >= 300)) {
                    if (retryable(response.statusCode()) && attempt < request.maximumAttempts()) {
                        pauseBeforeRetry(request, retryDelay(response, attempt));
                        continue;
                    }
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

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }

    private static Map<String, String> safeRedirectHeaders(Map<String, String> headers) {
        var result = new HashMap<String, String>();
        headers.forEach((name, value) -> {
            var normalized = name.toLowerCase(Locale.ROOT);
            if (!normalized.equals("authorization")
                    && !normalized.equals("proxy-authorization")
                    && !normalized.equals("cookie")
                    && !normalized.equals("x-api-key")
                    && !normalized.equals("x-subscription-token")) {
                result.put(name, value);
            }
        });
        return Map.copyOf(result);
    }

    private Map<String, String> withUserAgent(Map<String, String> headers) {
        var result = new HashMap<String, String>();
        headers.forEach((name, value) -> {
            if (!"user-agent".equalsIgnoreCase(name)) {
                result.put(name, value);
            }
        });
        result.put("User-Agent", userAgent);
        return Map.copyOf(result);
    }

    private static String requireHeaderValue(String value, String field, int maximumLength) {
        var normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static URI redirectTarget(URI currentTarget, Response response, Request request) {
        var location = response.redirectLocation();
        if (location == null || location.isBlank()) {
            throw new SourceCollectionException(
                    request.errorPrefix() + "_REDIRECT_INVALID",
                    request.safeName() + " returned a redirect without a valid location",
                    true);
        }
        final URI target;
        try {
            target = currentTarget.resolve(location.strip());
        } catch (IllegalArgumentException exception) {
            throw new SourceCollectionException(
                    request.errorPrefix() + "_REDIRECT_INVALID",
                    request.safeName() + " returned an invalid redirect location",
                    true);
        }
        if ("https".equalsIgnoreCase(currentTarget.getScheme())
                && !"https".equalsIgnoreCase(target.getScheme())) {
            throw new SourceCollectionException(
                    request.errorPrefix() + "_REDIRECT_DOWNGRADE_BLOCKED",
                    request.safeName() + " attempted to redirect from HTTPS to a non-HTTPS target",
                    true);
        }
        validateTarget(target, request);
        return target;
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    static Duration retryDelay(Response response, int attempt) {
        var retryAfter = response.responseHeaders().getOrDefault("retry-after", "");
        try {
            return Duration.ofSeconds(Math.max(1, Math.min(10, Long.parseLong(retryAfter))));
        } catch (NumberFormatException exception) {
            return response.statusCode() == 429
                    ? Duration.ofSeconds(5)
                    : Duration.ofSeconds(Math.min(4, attempt));
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

    private static void validateTarget(URI target, Request request) {
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
            String sourceId,
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
            sourceId = requireSourceId(sourceId);
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

        private static String requireSourceId(String value) {
            var normalized = requireText(value, "sourceId").toLowerCase(Locale.ROOT);
            if (!normalized.matches("source_[a-z0-9_-]{4,72}")) {
                throw new IllegalArgumentException("sourceId is invalid");
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
            String redirectLocation,
            String proxyRoute,
            int attempts,
            int redirectCount,
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
