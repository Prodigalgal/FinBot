package io.omnnu.finbot.infrastructure.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.network.ProxyGatewayControlGateway;
import io.omnnu.finbot.application.network.ProxyGatewayProfile;
import io.omnnu.finbot.application.network.ProxyGatewayRuntimeConfiguration;
import io.omnnu.finbot.application.network.ProxyGatewayRuntimeStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class JdkProxyGatewayControlGateway implements ProxyGatewayControlGateway {
    private static final String TOKEN_ENV = "FINBOT_JAVA_SERVICE_TOKEN";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final EnvironmentValueResolver environment;

    public JdkProxyGatewayControlGateway(
            @Qualifier("aiHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper,
            EnvironmentValueResolver environment) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public CompletionStage<Void> apply(
            ProxyGatewayProfile profile,
            ProxyGatewayRuntimeConfiguration configuration) {
        var token = environment.resolve(TOKEN_ENV)
                .orElseThrow(() -> new IllegalStateException("Proxy control token is not configured"));
        var request = HttpRequest.newBuilder(controlUri(profile.controlUrl()))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json(configuration)))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException(
                                "Proxy gateway rejected configuration with HTTP " + response.statusCode());
                    }
                    return null;
                });
    }

    @Override
    public CompletionStage<ProxyGatewayRuntimeStatus> status(ProxyGatewayProfile profile) {
        var token = environment.resolve(TOKEN_ENV)
                .orElseThrow(() -> new IllegalStateException("Proxy control token is not configured"));
        var request = HttpRequest.newBuilder(statusUri(profile.controlUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException(
                                "Proxy gateway status returned HTTP " + response.statusCode());
                    }
                    return runtimeStatus(profile.gatewayId(), response.body());
                });
    }

    private String json(ProxyGatewayRuntimeConfiguration configuration) {
        try {
            return objectMapper.writeValueAsString(configuration);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode proxy gateway configuration", exception);
        }
    }

    private ProxyGatewayRuntimeStatus runtimeStatus(String gatewayId, String body) {
        try {
            var root = objectMapper.readTree(body);
            var healthyNodeIndices = new ArrayList<Integer>();
            root.path("healthyNodeIndices").forEach(node -> healthyNodeIndices.add(node.asInt()));
            var probeFailureCounts = new LinkedHashMap<String, Integer>();
            root.path("probeFailureCounts").properties().forEach(entry ->
                    probeFailureCounts.put(entry.getKey(), entry.getValue().asInt()));
            return new ProxyGatewayRuntimeStatus(
                    gatewayId,
                    root.path("serviceReady").asBoolean(false),
                    root.path("ready").asBoolean(false),
                    root.path("nodeCount").asInt(0),
                    root.path("healthyNodeCount").asInt(0),
                    root.path("unhealthyNodeCount").asInt(0),
                    healthyNodeIndices,
                    probeFailureCounts,
                    root.path("validationEnabled").asBoolean(false),
                    nullableText(root.get("validationTarget")),
                    root.path("generation").asLong(0),
                    root.path("refreshAttempt").asLong(0),
                    epochSeconds(root.get("lastRefreshEpochSeconds")),
                    nullableText(root.get("lastError")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Proxy gateway returned invalid status JSON", exception);
        }
    }

    private static URI controlUri(URI baseUrl) {
        var base = baseUrl.toString().endsWith("/") ? baseUrl.toString() : baseUrl + "/";
        return URI.create(base).resolve("control/config?force=true");
    }

    private static URI statusUri(URI baseUrl) {
        var base = baseUrl.toString().endsWith("/") ? baseUrl.toString() : baseUrl + "/";
        return URI.create(base).resolve("control/status");
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() || !node.isTextual() ? null : node.textValue();
    }

    private static Instant epochSeconds(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return Instant.ofEpochMilli(Math.round(node.doubleValue() * 1000D));
    }
}
