package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerAccessChallenge;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeBypass;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeBypassGateway;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector;
import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfile;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.SourceId;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class CrawlerTransport {
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final int MAXIMUM_BYPASS_ATTEMPTS = 1;

    private final RoutedHttpClientFactory httpClients;
    private final CrawlerConcurrencyLimiter concurrencyLimiter;
    private final CrawlerPolitenessController politenessController;
    private final Clock clock;
    private final CrawlerRequestHeaderPolicy headerPolicy;
    private final CrawlerAccessChallengeDetector challengeDetector;
    private final CrawlerAccessChallengeBypassGateway challengeBypassGateway;

    public CrawlerTransport(
            RoutedHttpClientFactory httpClients,
            CrawlerConcurrencyLimiter concurrencyLimiter,
            CrawlerPolitenessController politenessController,
            Clock clock,
            CrawlerRequestHeaderPolicy headerPolicy,
            CrawlerAccessChallengeDetector challengeDetector,
            CrawlerAccessChallengeBypassGateway challengeBypassGateway) {
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.concurrencyLimiter = Objects.requireNonNull(concurrencyLimiter, "concurrencyLimiter");
        this.politenessController = Objects.requireNonNull(politenessController, "politenessController");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.headerPolicy = Objects.requireNonNull(headerPolicy, "headerPolicy");
        this.challengeDetector = Objects.requireNonNull(challengeDetector, "challengeDetector");
        this.challengeBypassGateway = Objects.requireNonNull(challengeBypassGateway, "challengeBypassGateway");
    }

    @SuppressWarnings("try")
    public Response get(Request request) {
        Objects.requireNonNull(request, "request");
        final CrawlerHeaderProfile profile;
        final Map<String, String> baseHeaders;
        try {
            profile = headerPolicy.resolveProfile(new SourceId(request.sourceId()));
            baseHeaders = headerPolicy.prepare(profile, request.headers());
        } catch (IllegalArgumentException exception) {
            throw new SourceCollectionException(
                    request.errorPrefix() + "_REQUEST_HEADERS_INVALID",
                    request.safeName() + " request headers are invalid",
                    true);
        }
        var bypass = CrawlerAccessChallengeBypass.empty();
        var bypassAttempts = 0;
        while (true) {
            var headers = applyBypass(baseHeaders, bypass);
            var result = followRedirects(request, profile, headers);
            if (result.statusCode() >= 200 && result.statusCode() < 300) {
                return result;
            }
            var challenge = challengeDetector.detect(
                    result.requestedUrl(),
                    result.statusCode(),
                    result.contentType(),
                    result.body(),
                    result.responseHeaders());
            var solvable = challenge.isPresent()
                    && challenge.get().kind() != CrawlerAccessChallenge.Kind.UNKNOWN_BLOCK
                    && challenge.get().kind() != CrawlerAccessChallenge.Kind.RATE_LIMITED;
            if (profile.captchaBypassEnabled()
                    && bypassAttempts < MAXIMUM_BYPASS_ATTEMPTS
                    && solvable) {
                bypass = challengeBypassGateway.solve(challenge.get(), profile.captchaBypassProvider())
                        .orElseThrow(() -> new SourceCollectionException(
                                request.errorPrefix() + "_CAPTCHA_BYPASS_UNAVAILABLE",
                                request.safeName() + " challenge bypass provider returned no solution for "
                                        + challenge.get().kind().name(),
                                true,
                                result.statusCode(),
                                challenge.get().kind().name()));
                bypassAttempts++;
                continue;
            }
            // C1 always classifies; C3 only retries when enabled and solvable.
            throw classifiedFailure(request, result, challenge);
        }
    }

    private static SourceCollectionException classifiedFailure(
            Request request,
            Response response,
            java.util.Optional<CrawlerAccessChallenge> challenge) {
        return SourceCollectionException.forAccessFailure(
                request.errorPrefix(),
                request.safeName(),
                response.statusCode(),
                challenge);
    }

    private Response followRedirects(
            Request request,
            CrawlerHeaderProfile profile,
            Map<String, String> initialHeaders) {
        var target = request.target();
        var headers = initialHeaders;
        var redirects = 0;
        var totalAttempts = 0;
        while (true) {
            validateTarget(target, request);
            var response = execute(request, target, headers);
            totalAttempts += response.attempts();
            if (!isRedirect(response.statusCode())) {
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
                headers = headerPolicy.forCrossOriginRedirect(profile, headers);
            }
            target = nextTarget;
            redirects++;
        }
    }

    private static Map<String, String> applyBypass(
            Map<String, String> baseHeaders,
            CrawlerAccessChallengeBypass bypass) {
        if (bypass.extraHeaders().isEmpty() && bypass.cookies().isEmpty()) {
            return baseHeaders;
        }
        var merged = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        baseHeaders.forEach(merged::put);
        bypass.extraHeaders().forEach(merged::put);
        var cookieHeader = bypass.cookieHeader();
        if (!cookieHeader.isBlank()) {
            var existing = headerValue(merged, "Cookie");
            if (existing == null || existing.isBlank()) {
                merged.put("Cookie", cookieHeader);
            } else {
                merged.put("Cookie", existing + "; " + cookieHeader);
            }
        }
        return Map.copyOf(merged);
    }

    private static String headerValue(Map<String, String> headers, String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
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
            headers.forEach((name, value) -> {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException restricted) {
                    // Host / Connection / hop-by-hop may still be restricted by the JDK client
                    // unless jdk.httpclient.allowRestrictedHeaders is configured at process start.
                    if (isStrictlyRestricted(name)) {
                        return;
                    }
                    throw restricted;
                }
            });
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
                    // Leave 401/403 for optional CAPTCHA bypass; still retry transient 5xx/429.
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

    private static boolean isStrictlyRestricted(String name) {
        var normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("connection")
                || normalized.equals("content-length")
                || normalized.equals("expect")
                || normalized.equals("host")
                || normalized.equals("upgrade");
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
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
        var result = new java.util.HashMap<String, String>();
        response.headers().firstValue("content-type").ifPresent(value -> result.put("content-type", value));
        response.headers().firstValue("etag").ifPresent(value -> result.put("etag", value));
        response.headers().firstValue("last-modified").ifPresent(value -> result.put("last-modified", value));
        response.headers().firstValue("retry-after").ifPresent(value -> result.put("retry-after", value));
        response.headers().firstValue("server").ifPresent(value -> result.put("server", value));
        response.headers().firstValue("cf-ray").ifPresent(value -> result.put("cf-ray", value));
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
