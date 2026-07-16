package io.omnnu.finbot.infrastructure.network;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.network.ProxyGatewayControlGateway;
import io.omnnu.finbot.application.network.ProxyGatewayProfile;
import io.omnnu.finbot.application.network.ProxyGatewayRuntimeConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

    private String json(ProxyGatewayRuntimeConfiguration configuration) {
        try {
            return objectMapper.writeValueAsString(configuration);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode proxy gateway configuration", exception);
        }
    }

    private static URI controlUri(URI baseUrl) {
        var base = baseUrl.toString().endsWith("/") ? baseUrl.toString() : baseUrl + "/";
        return URI.create(base).resolve("control/config");
    }
}
