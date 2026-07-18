package io.omnnu.finbot.infrastructure.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ExchangeAccountHttpTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeAccountHttpTransport.class);
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final RoutedHttpClientFactory httpClients;
    private final ObjectMapper objectMapper;
    private final int maximumAttempts;
    private final Duration initialBackoff;

    public ExchangeAccountHttpTransport(
            RoutedHttpClientFactory httpClients,
            ObjectMapper objectMapper,
            @Value("${finbot.exchange-account.maximum-attempts:3}") int maximumAttempts,
            @Value("${finbot.exchange-account.initial-backoff:PT0.5S}") Duration initialBackoff) {
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        if (maximumAttempts < 1 || maximumAttempts > 5) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 5");
        }
        this.maximumAttempts = maximumAttempts;
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
        if (initialBackoff.isNegative()
                || initialBackoff.isZero()
                || initialBackoff.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("initialBackoff must be between 1 nanosecond and 10 seconds");
        }
    }

    public Response send(
            OutboundRoute route,
            Supplier<HttpRequest> requestFactory) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(requestFactory, "requestFactory");
        var directFallback = false;
        for (var attempt = 1; attempt <= maximumAttempts; attempt++) {
            try {
                var resolvedRoute = httpClients.route(route);
                var effectiveRoute = directFallback
                        ? directRoute(resolvedRoute)
                        : resolvedRoute;
                var response = httpClients.clientForRequest(effectiveRoute).send(
                        requestFactory.get(),
                        HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try (var input = response.body()) {
                    bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
                }
                if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                    throw new IllegalStateException("Exchange account response exceeded the safety limit");
                }
                if (retryable(response.statusCode()) && attempt < maximumAttempts) {
                    retry(route, attempt, "HTTP_" + response.statusCode(), retryDelay(response, attempt));
                    continue;
                }
                try {
                    var json = bytes.length == 0
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(bytes);
                    return new Response(response.statusCode(), json, attempt);
                } catch (JsonProcessingException exception) {
                    if (attempt < maximumAttempts) {
                        retry(route, attempt, "INVALID_JSON", backoff(attempt));
                        continue;
                    }
                    throw new ExchangeAccountTransportException(
                            "EXCHANGE_ACCOUNT_INVALID_RESPONSE",
                            "Exchange account response remained invalid JSON after " + attempt + " attempts",
                            attempt,
                            exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExchangeAccountTransportException(
                        "EXCHANGE_ACCOUNT_INTERRUPTED",
                        "Exchange account request was interrupted",
                        attempt,
                        exception);
            } catch (IOException exception) {
                httpClients.invalidate(route);
                if (!directFallback) {
                    var resolvedRoute = httpClients.route(route);
                    if (resolvedRoute.proxied() && resolvedRoute.directAllowed()) {
                        directFallback = true;
                        LOGGER.warn(
                                "Exchange account proxy failed; switching to direct fallback; route={}, reason={}",
                                route,
                                exception.getClass().getSimpleName());
                    }
                }
                if (attempt < maximumAttempts) {
                    retry(route, attempt, exception.getClass().getSimpleName(), backoff(attempt));
                    continue;
                }
                throw new ExchangeAccountTransportException(
                        "EXCHANGE_ACCOUNT_NETWORK_FAILURE",
                        "Exchange account request failed after " + attempt + " attempts: "
                                + exception.getClass().getSimpleName(),
                        attempt,
                        exception);
            }
        }
        throw new IllegalStateException("Exchange account retry loop completed without a result");
    }

    private static ProxyRouteDecision directRoute(ProxyRouteDecision route) {
        return new ProxyRouteDecision(
                route.route(),
                false,
                true,
                null,
                route.expectedIpFamily(),
                "direct");
    }

    private void retry(
            OutboundRoute route,
            int attempt,
            String reason,
            Duration delay) {
        httpClients.invalidate(route);
        LOGGER.warn(
                "Retrying exchange account request; route={}, attempt={}/{}, reason={}, delayMs={}",
                route,
                attempt,
                maximumAttempts,
                reason,
                delay.toMillis());
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExchangeAccountTransportException(
                    "EXCHANGE_ACCOUNT_RETRY_INTERRUPTED",
                    "Exchange account retry was interrupted",
                    attempt,
                    exception);
        }
    }

    private Duration backoff(int attempt) {
        var multiplier = 1L << Math.min(attempt - 1, 3);
        return initialBackoff.multipliedBy(multiplier);
    }

    private Duration retryDelay(HttpResponse<?> response, int attempt) {
        var retryAfter = response.headers().firstValue("retry-after").orElse("");
        try {
            return Duration.ofSeconds(Math.max(1, Math.min(10, Long.parseLong(retryAfter))));
        } catch (NumberFormatException exception) {
            return backoff(attempt);
        }
    }

    private static boolean retryable(int statusCode) {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500 && statusCode <= 599;
    }

    public record Response(int statusCode, JsonNode json, int attempts) {
        public Response {
            Objects.requireNonNull(json, "json");
            if (attempts < 1) {
                throw new IllegalArgumentException("attempts must be positive");
            }
        }
    }
}
